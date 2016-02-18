package native
package compiler
package pass

import native.nir._

/** Lowers modules into module classes with singleton
 *  instance stored in a global variable that is accessed
 *  through a dedicated accessor function.
 *
 *  For example a module with members:
 *
 *      module $name : $parent, ..$ifaces {
 *        ..$members
 *      }
 *
 *  Translates to:
 *
 *      class $name : $parent, ..$ifaces {
 *        ..$members
 *      }
 *
 *      var ${name}_data: class $name = null
 *
 *      def ${name}_accessor: () => class $name = {
 *        %entry:
 *          %prev = load[class $name] ${name}_data
 *          %cond = eq[object] prev, null
 *          if %cond then %thenp else %elsep
 *        %thenp:
 *          %alloc = obj-alloc[class $name]
 *          call ${name}_init(%new)
 *          store[$name] ${name}_data, %alloc
 *          ret %alloc
 *        %elsep:
 *          ret %prev
 *      }
 *
 *  Eliminates:
 *  - Type.ModuleClass
 *  - Op.Module
 *  - Defn.Module
 */
class ModuleLowering(implicit fresh: Fresh) extends Pass {
  override def preDefn = {
    case Defn.Module(attrs, name, parent, ifaces, members) =>
      val cls = Defn.Class(attrs, name, parent, ifaces, members)
      val clsty = Type.Class(name)
      val ptrclsty = Type.Ptr(clsty)
      val ctorty = Type.Function(Seq(clsty), Type.Unit)
      val ctor = Val.Global(name + "init", Type.Ptr(ctorty))
      val data = Defn.Var(Seq(), name + "data", clsty, Val.Null)
      val dataval = Val.Global(data.name, ptrclsty)
      val accessor =
        Defn.Define(Seq(), name + "accessor", Type.Function(Seq(), Type.Class(name)),
          {
            val entry = Focus.entry(fresh)
            val prev = entry withOp Op.Load(clsty, dataval)
            val cond = prev withOp Op.Comp(Comp.Ieq, Intr.object_, prev.value, Val.Null)

            cond.branchIf(cond.value, Type.Nothing,
              { thenp =>
                val alloc = thenp withOp Op.Alloc(clsty)
                val call = alloc withOp Op.Call(ctorty, ctor, Seq(alloc.value))
                val store = call withOp Op.Store(clsty, dataval, alloc.value)
                store finish Op.Ret(alloc.value)
              },
              { elsep =>
                elsep finish Op.Ret(prev.value)
              }
            ).finish(Op.Unreachable).blocks
          }
        )
      Seq(cls, data, accessor)
  }

  override def preInst = {
    case Inst(Some(n), Op.Module(name)) =>
      val accessorTy = Type.Function(Seq(), Type.Class(name))
      val accessorVal = Val.Global(name + "accessor", Type.Ptr(accessorTy))
      Seq(Inst(n, Op.Call(accessorTy, accessorVal, Seq())))
  }

  override def preType = {
    case Type.ModuleClass(n) => Type.Class(n)
  }
}
