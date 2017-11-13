package scala.meta

import org.jetbrains.plugins.scala.DependencyManager
import org.jetbrains.plugins.scala.DependencyManager._
import org.jetbrains.plugins.scala.base.libraryLoaders.{LibraryLoader, ScalaLibraryLoader}
import org.jetbrains.plugins.scala.debugger.{ScalaSdkOwner, ScalaVersion, Scala_2_12}

import scala.meta.intellij.MetaExpansionsManager.META_MINOR_VERSION

trait ScalaMetaTestBase extends ScalaSdkOwner {
  override implicit val version: ScalaVersion = Scala_2_12
  override def librariesLoaders: Seq[LibraryLoader] = Seq(ScalaLibraryLoader(isIncludeReflectLibrary = true))
  protected val dependencyManager = DependencyManager("org.scalameta" %% "scalameta" % META_MINOR_VERSION transitive())
}