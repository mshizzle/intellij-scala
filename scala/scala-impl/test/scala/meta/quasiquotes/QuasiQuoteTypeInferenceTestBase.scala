package scala.meta.quasiquotes

import org.jetbrains.plugins.scala.lang.typeInference.TypeInferenceTestBase

import scala.meta.ScalaMetaTestBase

abstract class QuasiQuoteTypeInferenceTestBase extends TypeInferenceTestBase with ScalaMetaTestBase {

  override def loadIvyDependencies(): Unit = dependencyManager.loadAll

  override protected def doTest(fileText: String): Unit =
    super.doTest(
      s"""import scala.meta._
         |$fileText""".stripMargin)

}
