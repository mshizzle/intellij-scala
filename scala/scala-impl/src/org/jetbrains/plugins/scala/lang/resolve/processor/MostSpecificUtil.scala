package org.jetbrains.plugins.scala
package lang
package resolve
package processor

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi._
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPrimaryConstructor
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameterClause
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypedDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager
import org.jetbrains.plugins.scala.lang.psi.implicits.ImplicitResolveResult
import org.jetbrains.plugins.scala.lang.psi.types.Compatibility.Expression
import org.jetbrains.plugins.scala.lang.psi.types._
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.ScDesignatorType
import org.jetbrains.plugins.scala.lang.psi.types.api.{Any, Nothing, _}
import org.jetbrains.plugins.scala.lang.psi.types.nonvalue.{Parameter, ScMethodType, ScTypePolymorphicType}
import org.jetbrains.plugins.scala.lang.psi.types.result._
import org.jetbrains.plugins.scala.project.ProjectContext

import scala.collection.Set
import scala.collection.mutable.ArrayBuffer

/**
 * User: Alexander Podkhalyuzin
 * Date: 26.04.2010
 */
case class MostSpecificUtil(elem: PsiElement, length: Int) {
  implicit def ctx: ProjectContext = elem

  def mostSpecificForResolveResult(applicable: Set[ScalaResolveResult],
                                   hasTypeParametersCall: Boolean = false,
                                   expandInnerResult: Boolean = true): Option[ScalaResolveResult] = {
    mostSpecificGeneric(applicable.map(r => r.innerResolveResult match {
      case Some(rr) if expandInnerResult =>
        new InnerScalaResolveResult(rr.element, rr.implicitConversionClass, r, r.substitutor)
      case _ =>
        new InnerScalaResolveResult(r.element, r.implicitConversionClass, r, r.substitutor)
    }), noImplicit = false).map(_.repr)
  }

  def mostSpecificForImplicitParameters(applicable: Set[(ScalaResolveResult, ScSubstitutor)]): Option[ScalaResolveResult] = {
    mostSpecificGeneric(applicable.map{case (r, subst) => r.innerResolveResult match {
      case Some(rr) => new InnerScalaResolveResult(rr.element, rr.implicitConversionClass, r, subst, implicitCase = true)
      case None => new InnerScalaResolveResult(r.element, r.implicitConversionClass, r, subst, implicitCase = true)
    }}, noImplicit = true).map(_.repr)
  }

  def nextLayerSpecificForImplicitParameters(filterRest: Option[ScalaResolveResult],
                                             rest: Seq[ScalaResolveResult]): (Option[ScalaResolveResult], Seq[ScalaResolveResult]) = {
    val (next, r) = nextLayerSpecificGeneric(filterRest.map(toInnerSRR), rest.map(toInnerSRR))
    (next.map(_.repr), r.map(_.repr))
  }

  private def toInnerSRR(r: ScalaResolveResult): InnerScalaResolveResult[ScalaResolveResult] = {
    r.innerResolveResult match {
      case Some(rr) => new InnerScalaResolveResult(rr.element, rr.implicitConversionClass, r, ScSubstitutor.empty, implicitCase = true)
      case None => new InnerScalaResolveResult(r.element, r.implicitConversionClass, r, ScSubstitutor.empty, implicitCase = true)
    }
  }

  def nextMostSpecific(rest: Set[ScalaResolveResult]): Option[ScalaResolveResult] = {
    nextMostSpecificGeneric(rest.map(toInnerSRR)).map(_.repr)
  }

  def notMoreSpecificThan(result: ScalaResolveResult): (ScalaResolveResult) => Boolean = {
    val inner = toInnerSRR(result)

    cand => !isMoreSpecific(inner, toInnerSRR(cand), checkImplicits = false)
  }

  def filterLessSpecific(result: ScalaResolveResult, rest: Set[ScalaResolveResult]): Set[ScalaResolveResult] = {
    val inners = rest.map(toInnerSRR)
    val innerResult = toInnerSRR(result)
    inners.filter(!isMoreSpecific(innerResult, _, checkImplicits = false)).map(_.repr)
  }

  def mostSpecificForImplicit(applicable: Set[ImplicitResolveResult]): Option[ImplicitResolveResult] = {
    mostSpecificGeneric(applicable.map(r => {
      var callByName = false
      def checkCallByName(clauses: Seq[ScParameterClause]): Unit = {
        if (clauses.nonEmpty && clauses.head.parameters.length == 1 && clauses.head.parameters.head.isCallByNameParameter) {
          callByName = true
        }
      }
      r.element match {
        case f: ScFunction => checkCallByName(f.paramClauses.clauses)
        case f: ScPrimaryConstructor => checkCallByName(f.effectiveParameterClauses)
        case _ =>
      }
      new InnerScalaResolveResult(r.element, None, r, r.substitutor, callByName, implicitCase = true)
    }), noImplicit = true).map(_.repr)
  }

  private class InnerScalaResolveResult[T](val element: PsiNamedElement, val implicitConversionClass: Option[PsiClass],
                                           val repr: T, val substitutor: ScSubstitutor,
                                           val callByNameImplicit: Boolean = false,
                                           val implicitCase: Boolean = false)

  private def isAsSpecificAs[T](r1: InnerScalaResolveResult[T], r2: InnerScalaResolveResult[T],
                                checkImplicits: Boolean): Boolean = {
    def lastRepeated(params: Seq[Parameter]): Boolean = {
      val lastOption: Option[Parameter] = params.lastOption
      if (lastOption.isEmpty) return false
      lastOption.get.isRepeated
    }
    (r1.element, r2.element) match {
      case (m1@(_: PsiMethod | _: ScFun), m2@(_: PsiMethod | _: ScFun)) =>
        val (t1, t2) = (r1.substitutor.subst(getType(m1, r1.implicitCase)), r2.substitutor.subst(getType(m2, r2.implicitCase)))
        def calcParams(tp: ScType, existential: Boolean): Either[Seq[Parameter], ScType] = {
          tp match {
            case ScMethodType(_, params, _) => Left(params)
            case ScTypePolymorphicType(ScMethodType(_, params, _), typeParams) =>
              if (!existential) {
                val s: ScSubstitutor = typeParams.foldLeft(ScSubstitutor.empty) {
                  (subst: ScSubstitutor, tp: TypeParameter) =>
                    subst.bindT(tp.nameAndId,
                      UndefinedType(TypeParameterType(tp.psiTypeParameter, None)))
                }
                Left(params.map(p => p.copy(paramType = s.subst(p.paramType))))
              } else {
                val s: ScSubstitutor = typeParams.foldLeft(ScSubstitutor.empty) {
                  (subst: ScSubstitutor, tp: TypeParameter) =>
                    subst.bindT(tp.nameAndId,
                      ScExistentialArgument(tp.name, List.empty /* todo? */ , tp.lowerType, tp.upperType))
                }
                val arguments = typeParams.toList.map(tp =>
                  ScExistentialArgument(tp.name, List.empty /* todo? */ , s.subst(tp.lowerType), s.subst(tp.upperType)))
                Left(params.map(p => p.copy(paramType = ScExistentialType(s.subst(p.paramType), arguments))))
              }
            case ScTypePolymorphicType(internal, typeParams) =>
              if (!existential) {
                val s: ScSubstitutor = typeParams.foldLeft(ScSubstitutor.empty) {
                  (subst: ScSubstitutor, tp: TypeParameter) =>
                    subst.bindT(tp.nameAndId,
                      UndefinedType(TypeParameterType(tp.psiTypeParameter, None)))
                }
                Right(s.subst(internal))
              } else {
                val s: ScSubstitutor = typeParams.foldLeft(ScSubstitutor.empty) {
                  (subst: ScSubstitutor, tp: TypeParameter) =>
                    subst.bindT(tp.nameAndId,
                      ScExistentialArgument(tp.name, List.empty /* todo? */ , tp.lowerType, tp.upperType))
                }
                val arguments = typeParams.toList.map(tp =>
                  ScExistentialArgument(tp.name, List.empty /* todo? */ , s.subst(tp.lowerType), s.subst(tp.upperType)))
                Right(ScExistentialType(s.subst(internal), arguments))
              }
            case _ => Right(tp)
          }
        }

        val conformance = (calcParams(t1, existential = true), calcParams(t2, existential = false)) match {
            case (Left(p1), Left(p2)) =>
              var (params1, params2) = (p1, p2)
              if ((t1.isInstanceOf[ScTypePolymorphicType] && t2.isInstanceOf[ScTypePolymorphicType] ||
                      (!(m1.isInstanceOf[ScFunction] || m1.isInstanceOf[ScFun] || m1.isInstanceOf[ScPrimaryConstructor]) ||
                              !(m2.isInstanceOf[ScFunction] || m2.isInstanceOf[ScFun] || m2.isInstanceOf[ScPrimaryConstructor]))) &&
                      (lastRepeated(params1) ^ lastRepeated(params2))) return lastRepeated(params2) //todo: this is hack!!! see SCL-3846, SCL-4048
              if (lastRepeated(params1) && !lastRepeated(params2)) params1 = params1.map {
                case p: Parameter if p.isRepeated =>
                  val seq = ScalaPsiManager.instance(r1.element.getProject).getCachedClass(r1.element.resolveScope,
                    "scala.collection.Seq").orNull
                  if (seq != null) {
                    val newParamType = p.paramType match {
                      case ScExistentialType(q, wilds) =>
                        ScExistentialType(ScParameterizedType(ScDesignatorType(seq), Seq(q)), wilds)
                      case paramType => ScParameterizedType(ScDesignatorType(seq), Seq(paramType))
                    }
                    Parameter(p.name, p.deprecatedName, newParamType, p.expectedType,
                      p.isDefault, isRepeated = false, isByName = p.isByName)
                  }
                  else p
                case p => p
              }
              val i: Int = if (params1.nonEmpty) 0.max(length - params1.length) else 0
              val default: Expression =
                new Expression(if (params1.nonEmpty) params1.last.paramType else Nothing, elem)
              val exprs: Seq[Expression] = params1.map(p => new Expression(p.paramType, elem)) ++
                      Seq.fill(i)(default)
              Compatibility.checkConformance(checkNames = false, params2, exprs, checkImplicits)
            case (Right(type1), Right(type2)) =>
              type1.conforms(type2, ScUndefinedSubstitutor()) //todo: with implcits?
            //todo this is possible, when one variant is empty with implicit parameters, and second without parameters.
            //in this case it's logical that method without parameters must win...
            case (Left(_), Right(_)) if !r1.implicitCase => return false
            case _ => return true
          }

        var u = conformance._2
        if (!conformance._1) return false

        t2 match {
          case ScTypePolymorphicType(_, typeParams) =>
            u.getSubstitutor match {
              case Some(uSubst) =>

                val nameAndIds = typeParams.map(_.nameAndId).toSet
                def hasRecursiveTypeParameters(typez: ScType): Boolean = typez.hasRecursiveTypeParameters(nameAndIds)

                typeParams.foreach(tp => {
                  if (tp.lowerType != Nothing) {
                    val substedLower = uSubst.subst(tp.lowerType)
                    if (!hasRecursiveTypeParameters(tp.lowerType)) {
                      u = u.addLower(tp.nameAndId, substedLower, additional = true)
                    }
                  }
                  if (tp.upperType != Any) {
                    val substedUpper = uSubst.subst(tp.upperType)
                    if (!hasRecursiveTypeParameters(tp.upperType)) {
                      u = u.addUpper(tp.nameAndId, substedUpper, additional = true)
                    }
                  }
                })
              case None => return false
            }
          case _ =>
        }
        u.getSubstitutor.isDefined
      case (_, _: PsiMethod) => true
      case (e1, e2) =>
        val t1: ScType = getType(e1, r1.implicitCase)
        val t2: ScType = getType(e2, r2.implicitCase)
        t1.conforms(t2)
    }
  }

  private def getClazz[T](r: InnerScalaResolveResult[T]): Option[PsiClass] = {
    val element = ScalaPsiUtil.nameContext(r.element)
    element match {
      case memb: PsiMember => Option(memb.containingClass)
      case _ => None
    }
  }

  /**
   * c1 is a subclass of c2, or
   * c1 is a companion object of a class derived from c2, or
   * c2 is a companion object of a class from which c1 is derived.
    *
    * @return true is c1 is derived from c2, false if c1 or c2 is None
   */
  def isDerived(c1: Option[PsiClass], c2: Option[PsiClass]): Boolean = {
    (c1, c2) match {
      case (Some(clazz1), Some(clazz2)) =>
        if (clazz1 == clazz2) return false
        if (ScalaPsiUtil.isInheritorDeep(clazz1, clazz2)) return true
        (clazz1, clazz2) match {
          case (clazz1: ScObject, _) => isDerived(ScalaPsiUtil.getCompanionModule(clazz1), Some(clazz2))
          case (_, clazz2: ScObject) => isDerived(Some(clazz1), ScalaPsiUtil.getCompanionModule(clazz2))
          case _ => false
        }
      case _ => false
    }
  }

  private def relativeWeight[T](r1: InnerScalaResolveResult[T], r2: InnerScalaResolveResult[T],
                                checkImplicits: Boolean): Int = {
    val s1 = if (isAsSpecificAs(r1, r2, checkImplicits)) 1 else 0
    val s2 = if (isDerived(getClazz(r1), getClazz(r2))) 1 else 0
    s1 + s2
  }

  private def isMoreSpecific[T](r1: InnerScalaResolveResult[T], r2: InnerScalaResolveResult[T], checkImplicits: Boolean): Boolean = {
    ProgressManager.checkCanceled()
    (r1.implicitConversionClass, r2.implicitConversionClass) match {
      case (Some(t1), Some(t2)) => if (ScalaPsiUtil.isInheritorDeep(t1, t2)) return true
      case _ =>
    }
    if (r1.callByNameImplicit ^ r2.callByNameImplicit) return !r1.callByNameImplicit
    val weightR1R2 = relativeWeight(r1, r2, checkImplicits)
    val weightR2R1 = relativeWeight(r2, r1, checkImplicits)
    weightR1R2 > weightR2R1
  }

  private def mostSpecificGeneric[T](applicable: Set[InnerScalaResolveResult[T]],
                                     noImplicit: Boolean): Option[InnerScalaResolveResult[T]] = {
    def calc(checkImplicits: Boolean): Option[InnerScalaResolveResult[T]] = {
      val a1iterator = applicable.iterator
      while (a1iterator.hasNext) {
        val a1 = a1iterator.next()
        var break = false
        val a2iterator = applicable.iterator
        while (a2iterator.hasNext && !break) {
          val a2 = a2iterator.next()
          if (a1 != a2 && !isMoreSpecific(a1, a2, checkImplicits)) break = true
        }
        if (!break) return Some(a1)
      }
      None
    }
    val result = calc(checkImplicits = false)
    if (!noImplicit && result.isEmpty) calc(checkImplicits = true)
    else result
  }

  private def nextLayerSpecificGeneric[T](filterRest: Option[InnerScalaResolveResult[T]],
                                          rest: Seq[InnerScalaResolveResult[T]]): (Option[InnerScalaResolveResult[T]], Seq[InnerScalaResolveResult[T]]) = {

    val filteredRest = filterRest match {
      case Some(r) => rest.filter(!isMoreSpecific(r, _, checkImplicits = false))
      case _ => rest
    }
    if (filteredRest.isEmpty) return (None, Seq.empty)
    if (filteredRest.length == 1) return (Some(filteredRest.head), Seq.empty)
    var found = filteredRest.head
    val iter = filteredRest.tail.iterator
    val out: ArrayBuffer[InnerScalaResolveResult[T]] = new ArrayBuffer[InnerScalaResolveResult[T]]()

    while (iter.hasNext) {
      val res = iter.next()
      if (isDerived(getClazz(res), getClazz(found))) {
        out += found
        found = res
      } else out += res
    }
    (Some(found), out)
  }

  private def nextMostSpecificGeneric[T](rest: Set[InnerScalaResolveResult[T]]): Option[InnerScalaResolveResult[T]] = {
    if (rest.isEmpty) return None
    if (rest.size == 1) return Some(rest.head)

    val iter = rest.iterator
    var foundMax = iter.next

    while (iter.hasNext) {
      val res = iter.next()
      if (isDerived(getClazz(res), getClazz(foundMax)))
        foundMax = res
    }
    Some(foundMax)
  }

  //todo: implement existential dual
  def getType(e: PsiNamedElement, implicitCase: Boolean): ScType = {
    val res = e match {
      case fun: ScFun => fun.polymorphicType
      case f: ScFunction if f.isConstructor =>
        f.containingClass match {
          case td: ScTypeDefinition if td.hasTypeParameters =>
            ScTypePolymorphicType(f.methodType, td.typeParameters.map(TypeParameter(_)))
          case _ => f.polymorphicType()
        }
      case f: ScFunction => f.polymorphicType()
      case p: ScPrimaryConstructor => p.polymorphicType
      case m: PsiMethod => ResolveUtils.javaPolymorphicType(m, ScSubstitutor.empty, elem.resolveScope)
      case refPatt: ScReferencePattern => refPatt.getParent /*id list*/ .getParent match {
        case pd: ScPatternDefinition if PsiTreeUtil.isContextAncestor(pd, elem, true) =>
          pd.declaredType.getOrElse(Nothing)
        case vd: ScVariableDefinition if PsiTreeUtil.isContextAncestor(vd, elem, true) =>
          vd.declaredType.getOrElse(Nothing)
        case _ => refPatt.`type`().getOrAny
      }
      case typed: ScTypedDefinition => typed.`type`().getOrAny
      case f: PsiField => f.getType.toScType()
      case _ => Nothing
    }

    res match {
      case ScMethodType(retType, _, true) if implicitCase => retType
      case ScTypePolymorphicType(ScMethodType(retType, _, true), typeParameters) if implicitCase =>
        ScTypePolymorphicType(retType, typeParameters)
      case tp => tp
    }
  }
}
