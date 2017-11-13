package org.jetbrains.plugins.scala.failed.resolve

import com.intellij.openapi.module.Module
import org.jetbrains.plugins.scala.lang.psi.api.base.ScReferenceElement
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveTestCase
import org.junit.Assert._

/**
  * @author Nikolay.Tropin
  */
abstract class FailedResolveTest(dirName: String) extends ScalaResolveTestCase {

  implicit def moduleContext: Module = module()

  override def folderPath(): String = s"${super.folderPath()}resolve/failed/$dirName"

  override def rootPath(): String = folderPath()

  def doTest(): Unit = {
    findReferenceAtCaret() match {
      case ref: ScReferenceElement =>
        val variants = ref.multiResolve(false)
        assertTrue(s"Single resolve expected, was: ${variants.length}", variants.length == 1)
    }
  }
}
