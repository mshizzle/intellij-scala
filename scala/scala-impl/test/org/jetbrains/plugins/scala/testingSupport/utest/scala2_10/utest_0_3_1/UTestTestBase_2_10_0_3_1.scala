package org.jetbrains.plugins.scala.testingSupport.utest.scala2_10.utest_0_3_1

import org.jetbrains.plugins.scala.DependencyManager
import org.jetbrains.plugins.scala.DependencyManager._
import org.jetbrains.plugins.scala.debugger.{ScalaVersion, Scala_2_10}
import org.jetbrains.plugins.scala.testingSupport.utest.UTestTestCase

/**
  * @author Roman.Shein
  * @since 02.09.2015.
  */
abstract class UTestTestBase_2_10_0_3_1 extends UTestTestCase {

  override implicit val version: ScalaVersion = Scala_2_10

  override protected def loadIvyDependencies(): Unit = DependencyManager(
    "com.lihaoyi"     %% "utest"        % "0.3.1",
    "org.scalamacros" %% "quasiquotes"  % "2.0.0"
  ).loadAll
}
