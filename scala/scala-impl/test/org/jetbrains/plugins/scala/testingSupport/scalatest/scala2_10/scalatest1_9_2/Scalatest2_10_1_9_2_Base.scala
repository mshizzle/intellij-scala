package org.jetbrains.plugins.scala.testingSupport.scalatest.scala2_10.scalatest1_9_2

import org.jetbrains.plugins.scala.DependencyManager
import org.jetbrains.plugins.scala.DependencyManager._
import org.jetbrains.plugins.scala.debugger.{ScalaVersion, Scala_2_10}
import org.jetbrains.plugins.scala.testingSupport.scalatest.ScalaTestTestCase

/**
  * @author Roman.Shein
  * @since 11.02.2015.
  */
abstract class Scalatest2_10_1_9_2_Base extends ScalaTestTestCase {

  override implicit val version: ScalaVersion = Scala_2_10

  override protected def loadIvyDependencies(): Unit = DependencyManager(
    "org.scalatest" %% "scalatest" % "1.9.2"
  ).loadAll
}
