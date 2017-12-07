package org.jetbrains.plugins.scala.debugger

import java.util.concurrent.ConcurrentMap

import com.intellij.debugger.engine.SyntheticTypeComponentProvider
import com.intellij.util.containers.ContainerUtil
import com.sun.jdi._
import org.jetbrains.plugins.scala.debugger.evaluation.util.DebuggerUtil.isScala
import org.jetbrains.plugins.scala.decompiler.DecompilerUtil

import scala.collection.JavaConverters
import scala.util.Try

/**
  * Nikolay.Tropin
  * 2014-12-03
  */
class ScalaSyntheticProvider extends SyntheticTypeComponentProvider {

  override final def isSynthetic(typeComponent: TypeComponent): Boolean = typeComponent match {
    case null => true
    case _ =>
      import ScalaSyntheticProvider._
      cache.get(typeComponent) match {
        case null =>
          typeComponent.declaringType() match {
            case referenceType if isScala(referenceType, default = false) =>
              cache.putIfAbsent(typeComponent, isSyntheticImpl(typeComponent, referenceType))
            case _ => false
          }
        case cached => cached
      }
  }
}

object ScalaSyntheticProvider {
  private val cache: ConcurrentMap[TypeComponent, java.lang.Boolean] = ContainerUtil.createConcurrentWeakMap()

  private def isSyntheticImpl: (TypeComponent, ReferenceType) => Boolean = {
    case (typeComponent, referenceType) if hasSpecialization(typeComponent, referenceType) && !isMacroDefined(typeComponent, referenceType) => true
    case (m: Method, referenceType) if m.isConstructor && ScalaPositionManager.isAnonfunType(referenceType) => true
    case (method: Method, referenceType) if isDefaultArg(method, referenceType) => true
    case (method: Method, referenceType) if isTraitForwarder(method, referenceType) => true
    case (method: Method, _) if method.name().endsWith("$adapted") => true
    case (m: Method, _) if ScalaPositionManager.isIndyLambda(m) => false
    case (method: Method, classType: ClassType) if isAccessorInDelayedInit(method, classType) => true
    case (field: Field, _) if field.name().startsWith("bitmap$") => true
    case (typeComponent, _) =>
      Option(typeComponent.virtualMachine).exists(_.canGetSyntheticAttribute) && typeComponent.isSynthetic
  }

  def unspecializedName(s: String): Option[String] = """.*(?=\$mc\w+\$sp)""".r.findFirstIn(s)

  def hasSpecialization(typeComponent: TypeComponent, referenceType: ReferenceType): Boolean = {
    import JavaConverters._
    val members = typeComponent match {
      case _: Method =>
        val signature = typeComponent.signature()
        referenceType.methods().asScala.filter(_.signature() == signature)
      case _: Field => referenceType.allFields().asScala
      case _ => Seq.empty
    }

    val name = typeComponent.name()
    members.map(_.name())
      .flatMap(unspecializedName)
      .contains(name)
  }

  def isSpecialization(tc: TypeComponent): Boolean = unspecializedName(tc.name()).nonEmpty

  private val defaultArgPattern = """\$default\$\d+""".r

  private def isDefaultArg(method: Method, referenceType: ReferenceType): Boolean = method.name() match {
    case methodName if methodName.contains("$default$") =>
      val lastDefault = defaultArgPattern.findAllMatchIn(methodName).toSeq.lastOption
      lastDefault.map(_.matched) match {
        case Some(s) if methodName.endsWith(s) =>
          val origMethodName = methodName.stripSuffix(s)
          !referenceType.methodsByName(origMethodName).isEmpty
        case _ => false
      }
    case _ => false
  }

  private def isTraitForwarder(method: Method, referenceType: ReferenceType): Boolean = {
    //trait forwarders are usually generated with line number of the containing class
    def looksLikeForwarderLocation: Boolean = {
      val line = method.location().lineNumber()
      if (line < 0) return false

      import JavaConverters._
      val lines = referenceType.methods().asScala
        .flatMap(m => Option(m.location()))
        .map(_.lineNumber())
        .filter(_ >= 0)

      lines.nonEmpty && line <= lines.min
    }

    def hasTraitWithImplementation = referenceType match {
      case interfaceType: InterfaceType => this.hasTraitWithImplementation(method, interfaceType)
      case classType: ClassType => this.hasTraitWithImplementation(method, classType)
      case _ => false
    }

    Try(onlyInvokesStatic(method) && hasTraitWithImplementation && looksLikeForwarderLocation).getOrElse(false)
  }

  def isMacroDefined(typeComponent: TypeComponent, referenceType: ReferenceType): Boolean = {
    val typeName = referenceType.name()
    typeName.contains("$macro") || typeName.contains("$stateMachine$")
  }

  private def onlyInvokesStatic(m: Method): Boolean = {
    val bytecodes: Array[Byte] =
      try m.bytecodes()
      catch {
        case _: Throwable => return false
      }

    var i = 0
    while (i < bytecodes.length) {
      val instr = bytecodes(i)
      if (BytecodeUtil.twoBytesLoadCodes.contains(instr)) i += 2
      else if (BytecodeUtil.oneByteLoadCodes.contains(instr)) i += 1
      else if (instr == DecompilerUtil.Opcodes.invokeStatic) {
        val nextIdx = i + 3
        val nextInstr = bytecodes(nextIdx)
        return nextIdx == (bytecodes.length - 1) && BytecodeUtil.returnCodes.contains(nextInstr)
      }
      else return false
    }
    false
  }

  import JavaConverters._

  private[this] def hasTraitWithImplementation(method: Method, interfaceType: InterfaceType): Boolean =
    interfaceType.methodsByName(method.name + "$").asScala match {
      case Seq() => false
      case methods =>
        val typeNames = method.argumentTypeNames()
        val argCount = typeNames.size
        methods.exists { impl =>
          val implTypeNames = impl.argumentTypeNames()
          val implArgCount = implTypeNames.size
          implArgCount == argCount + 1 && implTypeNames.asScala.tail == typeNames ||
            implArgCount == argCount && implTypeNames == typeNames
        }
    }

  private[this] def hasTraitWithImplementation(method: Method, classType: ClassType): Boolean = {
    val interfaces = classType.allInterfaces().asScala
    val vm = classType.virtualMachine()
    val allTraitImpls = vm.allClasses().asScala.filter(_.name().endsWith("$class"))
    for {
      interface <- interfaces
      traitImpl <- allTraitImpls
      if traitImpl.name().stripSuffix("$class") == interface.name() && !traitImpl.methodsByName(method.name).isEmpty
    } {
      return true
    }
    false
  }

  private[this] def isAccessorInDelayedInit(method: Method, classType: ClassType): Boolean = {
    val simpleName = method.name.stripSuffix("_$eq")
    classType.fieldByName(simpleName) != null &&
      classType.allInterfaces().asScala
        .map(_.name())
        .contains("scala.DelayedInit")
  }
}
