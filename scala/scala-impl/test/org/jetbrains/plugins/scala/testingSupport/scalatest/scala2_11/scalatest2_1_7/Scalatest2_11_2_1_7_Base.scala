package org.jetbrains.plugins.scala.testingSupport.scalatest.scala2_11.scalatest2_1_7

import org.jetbrains.plugins.scala.DependencyManager
import org.jetbrains.plugins.scala.DependencyManager._
import org.jetbrains.plugins.scala.testingSupport.scalatest.ScalaTestTestCase

/**
 * @author Roman.Shein
 * @since 22.01.2015
 */
abstract class Scalatest2_11_2_1_7_Base extends ScalaTestTestCase {

  override protected def loadIvyDependencies(): Unit = DependencyManager(
    "org.scalatest" %% "scalatest" % "2.1.7"
  ).loadAll

}
