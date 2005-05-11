/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.typechecker;

/** Variances form a lattice, 0 <= COVARIANT <= Variances, 0 <= CONTRAVARIANT <= VARIANCES
 */
abstract class Variances: Analyzer {

  import global._;
  import symtab.Flags._;

  /** Flip between covariant and contravariant */
  private def flip(v: int): int = {
    if (v == COVARIANT) CONTRAVARIANT;
    else if (v == CONTRAVARIANT) COVARIANT;
    else v
  }

  /** Map everything below VARIANCES to 0 */
  private def cut(v: int): int =
    if (v == VARIANCES) v else 0;

  /** Compute variance of type parameter `tparam' in types of all symbols `sym'. */
  def varianceInSyms(syms: List[Symbol])(tparam: Symbol): int =
    (VARIANCES /: syms) ((v, sym) => v & varianceInSym(sym)(tparam));

  /** Compute variance of type parameter `tparam' in type of symbol `sym'. */
  def varianceInSym(sym: Symbol)(tparam: Symbol): int = 
    if (sym.isAliasType) cut(varianceInType(sym.info)(tparam)) 
    else varianceInType(sym.info)(tparam);

  /** Compute variance of type parameter `tparam' in all types `tps'. */
  def varianceInTypes(tps: List[Type])(tparam: Symbol): int =
    (VARIANCES /: tps) ((v, tp) => v & varianceInType(tp)(tparam));

  /** Compute variance of type parameter `tparam' in all type arguments
   *  `tps' which correspond to formal type parameters `tparams1'. */
  def varianceInArgs(tps: List[Type], tparams1: List[Symbol])(tparam: Symbol): int = {
    var v: int = VARIANCES;
    for (val Pair(tp, tparam1) <- tps zip tparams1) {
      val v1 = varianceInType(tp)(tparam);
      v = v & (if (tparam1.hasFlag(COVARIANT)) v1
	       else if (tparam1.hasFlag(CONTRAVARIANT)) flip(v1)
	       else cut(v1))
    }
    v
  }

  /** Compute variance of type parameter `tparam' in type `tp'. */
  def varianceInType(tp: Type)(tparam: Symbol): int = tp match {
    case ErrorType | WildcardType | NoType | NoPrefix | ThisType(_) | ConstantType(_, _) =>
      VARIANCES
    case SingleType(pre, sym) =>
      cut(varianceInType(pre)(tparam))
    case TypeRef(pre, sym, args) =>
      if (sym == tparam) COVARIANT
      else varianceInType(pre)(tparam) & varianceInArgs(args, sym.typeParams)(tparam)
    case TypeBounds(lo, hi) =>
      flip(varianceInType(lo)(tparam)) & varianceInType(hi)(tparam)
    case RefinedType(parents, defs) =>
      varianceInTypes(parents)(tparam) & varianceInSyms(defs.toList)(tparam)
    case MethodType(formals, restpe) =>
      flip(varianceInTypes(formals)(tparam)) & varianceInType(restpe)(tparam)
    case PolyType(tparams, restpe) =>
      flip(varianceInSyms(tparams)(tparam)) & varianceInType(restpe)(tparam)
  }
}
