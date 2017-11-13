package org.jetbrains.plugins.scala.base.libraryLoaders

import java.io.File

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.{JarFileSystem, VirtualFile}
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.plugins.scala.DependencyManager._
import org.jetbrains.plugins.scala.{DependencyManager, ScalaLoader}
import org.jetbrains.plugins.scala.base.libraryLoaders.IvyLibraryLoader._
import org.jetbrains.plugins.scala.debugger.ScalaVersion
import org.jetbrains.plugins.scala.extensions.inWriteAction
import org.jetbrains.plugins.scala.project.template.Artifact.ScalaCompiler.versionOf
import org.jetbrains.plugins.scala.project.{LibraryExt, ModuleExt, ScalaLanguageLevel}

import scala.collection.JavaConverters._

case class ScalaSDKLoader(isIncludeReflectLibrary: Boolean = false) extends LibraryLoader {
  override def init(implicit module: Module, version: ScalaVersion): Unit = {

    val deps = Seq(
      "org.scala-lang" % "scala-compiler" % version.minor,
      "org.scala-lang" % "scala-library" % version.minor,
      "org.scala-lang" % "scala-reflect" % version.minor

    )

    val resolved = deps.flatMap(new DependencyManager().resolve(_))
      .filterNot(!isIncludeReflectLibrary && _.info.artId.contains("reflect"))

    val srcsResolved = new DependencyManager()
      .resolve("org.scala-lang" % "scala-library" % version.minor % Types.SRC)

    val library = PsiTestUtil.addProjectLibrary(module, "scala-sdk", resolved.map(_.toJarVFile).asJava, srcsResolved.map(_.toJarVFile).asJava)

    Disposer.register(module, library)

    inWriteAction {
      library.convertToScalaSdkWith(languageLevel(resolved.head.file), resolved.map(_.file))
      module.attach(library)
    }

    ScalaLoader.loadScala()
  }

  private def languageLevel(compiler: File) =
    versionOf(compiler)
      .flatMap(_.toLanguageLevel)
      .getOrElse(ScalaLanguageLevel.Default)
}

object ScalaLibraryLoader {

  ScalaLoader.loadScala()

  abstract class ScalaLibraryLoaderAdapter extends IvyLibraryLoader {

    override val vendor: String = "org.scala-lang"

    override def path(implicit version: ScalaVersion): String = super.path

    def rootFiles(implicit version: ScalaVersion): Seq[VirtualFile] = {
      val fileSystem = JarFileSystem.getInstance
      Option(fileSystem.refreshAndFindFileByPath(s"$path!/")).toSeq
    }

    override def init(implicit module: Module, version: ScalaVersion): Unit = ()

    override def folder(implicit version: ScalaVersion): String =
      name

    override def fileName(implicit version: ScalaVersion): String =
      s"$name-${version.minor}"
  }

  case class ScalaCompilerLoader() extends ScalaLibraryLoaderAdapter {

    override val name: String = "scala-compiler"
  }

  case class ScalaRuntimeLoader(override val ivyType: IvyType = Jars)
    extends ScalaLibraryLoaderAdapter {

    override val name: String = "scala-library"

    override def fileName(implicit version: ScalaVersion): String = {
      val suffix = ivyType match {
        case Sources => "-sources"
        case _ => ""
      }
      super.fileName + suffix
    }
  }

  case class ScalaReflectLoader() extends ScalaLibraryLoaderAdapter {

    override val name: String = "scala-reflect"
  }

}