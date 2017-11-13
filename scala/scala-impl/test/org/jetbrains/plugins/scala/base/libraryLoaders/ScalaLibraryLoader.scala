package org.jetbrains.plugins.scala.base.libraryLoaders

import java.io.File

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.{ProjectJdkTable, Sdk}
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.{JarFileSystem, VirtualFile}
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.plugins.scala.ScalaLoader
import org.jetbrains.plugins.scala.base.libraryLoaders.IvyLibraryLoader._
import org.jetbrains.plugins.scala.debugger.ScalaVersion
import org.jetbrains.plugins.scala.extensions.inWriteAction
import org.jetbrains.plugins.scala.project.template.Artifact.ScalaCompiler.versionOf
import org.jetbrains.plugins.scala.project.{LibraryExt, ModuleExt, ScalaLanguageLevel}
import org.jetbrains.plugins.scala.util.TestUtils

import scala.collection.JavaConverters._
import org.jetbrains.plugins.scala.DependencyManager._
import org.jetbrains.plugins.scala.DependencyManager

case class ScalaLibraryLoader(isIncludeReflectLibrary: Boolean = false)
  extends LibraryLoader {

  import ScalaLibraryLoader._

  private var library: Library = _

  def init(implicit module: Module, version: ScalaVersion) = {
    addScalaSdk
  }

  override def clean(implicit module: Module): Unit = {
    if (library != null) {
      inWriteAction {
        module.detach(library)
        library.getTable.removeLibrary(library)
      }
    }
  }

  private def addScalaSdk(implicit module: Module, version: ScalaVersion): Unit = {
    val loaders = Seq(ScalaCompilerLoader(), ScalaRuntimeLoader()) ++
      (if (isIncludeReflectLibrary) Seq(ScalaReflectLoader()) else Seq.empty)

    val files = loaders.map(loader => new File(loader.path))

    val classRoots = loaders.flatMap(_.rootFiles)
    val srcRoots = ScalaRuntimeLoader(Sources).rootFiles

    library = PsiTestUtil.addProjectLibrary(module, "scala-sdk", classRoots.asJava, srcRoots.asJava)
    Disposer.register(module, library)

    inWriteAction {
      library.convertToScalaSdkWith(languageLevel(files.head), files)

      module.attach(library)
    }
  }

  private def languageLevel(compiler: File) =
    versionOf(compiler)
      .flatMap(_.toLanguageLevel)
      .getOrElse(ScalaLanguageLevel.Default)
}

case class ScalaSDKLoader(includeScalaReflect: Boolean = false, jdk: Option[Sdk] = None) extends LibraryLoader {
  override def init(implicit module: Module, version: ScalaVersion): Unit = {

    def toVFile(dep: ResolvedDependency): VirtualFile =
      JarFileSystem.getInstance().refreshAndFindFileByPath(s"${dep.file.getCanonicalPath}!/")

    val deps = Seq(
      "org.scala-lang" % "scala-compiler" % version.minor,
      "org.scala-lang" % "scala-library" % version.minor,
      "org.scala-lang" % "scala-reflect" % version.minor

    )

    val resolved = deps.flatMap(new DependencyManager().resolve(_))
      .filterNot(!includeScalaReflect && _.info.artId.contains("reflect"))

    val srcsResolved = new DependencyManager()
      .resolve("org.scala-lang" % "scala-library" % version.minor % Types.SRC)

    val library = PsiTestUtil.addProjectLibrary(module, "scala-sdk", resolved.map(toVFile).asJava, srcsResolved.map(toVFile).asJava)

    Disposer.register(module, library)

    inWriteAction {
      library.convertToScalaSdkWith(languageLevel(resolved.head.file), resolved.map(_.file))
      module.attach(library)
    }

    jdk.foreach { ModuleRootModificationUtil.setModuleSdk(module, _) }

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

case class JdkLoader(jdk: Sdk = TestUtils.createJdk()) extends LibraryLoader {

  override def init(implicit module: Module, version: ScalaVersion): Unit = {
    val model = module.modifiableModel
    model.setSdk(jdk)
    inWriteAction {
      model.commit()
    }
  }

  override def clean(implicit module: Module): Unit = {
    val model = module.modifiableModel
    model.setSdk(null)
    inWriteAction {
      model.commit()
      ProjectJdkTable.getInstance().removeJdk(jdk)
    }
  }
}