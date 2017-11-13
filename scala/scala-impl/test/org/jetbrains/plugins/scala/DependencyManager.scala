package org.jetbrains.plugins.scala

import java.io.File
import java.nio.file.{Files, Paths}

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import org.apache.ivy.Ivy
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.resolver.{ChainResolver, URLResolver}
import org.jetbrains.plugins.scala.DependencyManager.Dependency
import org.jetbrains.plugins.scala.debugger.ScalaVersion

/**
  * SBT-like dependency manager for libraries to be used in tests.
  *
  * To use, override [[org.jetbrains.plugins.scala.debugger.ScalaSdkOwner#loadIvyDependencies()]],<br/>
  * create a new manager and call<br/> [[org.jetbrains.plugins.scala.DependencyManager#loadAll]] or [[org.jetbrains.plugins.scala.DependencyManager#load]]<br/>
  * {{{
  * override protected def loadIvyDependencies(): Unit =
  *     DependencyManager("com.chuusai" %% "shapeless" % "2.3.2").loadAll
  * }}}
  *
  * One can also do this outside loadIvyDependencies, but make sure that all loading is done before [[com.intellij.testFramework.LightPlatformTestCase#setUp()]]
  * is finished to avoid getting "Virtual pointer hasn't been disposed: " errors on tearDown()
  */
class DependencyManager(val deps: Dependency*) {
  import DependencyManager._

  private val homePrefix = sys.props.get("tc.idea.prefix").orElse(sys.props.get("user.home")).map(new File(_)).get
  private val ivyHome = sys.props.get("sbt.ivy.home").map(new File(_)).orElse(Option(new File(homePrefix, ".ivy2"))).get

  private var resolvers = Seq(
    Resolver("central", "http://repo1.maven.org/maven2/[organisation]/[module]/[revision]/[artifact](-[revision]).jar"),
    Resolver("scalaz-releases", "http://dl.bintray.com/scalaz/releases/[organisation]/[module]/[revision]/[artifact](-[revision]).jar")
  )

  private def mkIvyXml(dep: Dependency): String = {
    s"""
      |<ivy-module version="2.0">
      |<info organisation="org.jetbrains.plugins.scala" module="ij-scala-tests"/>
      | <configurations>
      |   <conf name="default"/>
      |   <conf name="compile"/>
      |   <conf name="test"/>
      | </configurations>
      |  <dependencies>
      |    <dependency org="${dep.org}" name="${dep.artId}" rev="${dep.version}" conf="${dep.conf}">
      |      <artifact name="${dep.artId}" type="${dep.kind}"/>
      |    </dependency>
      |  </dependencies>
      |</ivy-module>
    """.stripMargin
  }

  private def resolveIvy(d: Dependency): Seq[ResolvedDependency] = {
    def mkResolver(r: Resolver): URLResolver = {
      val resolver: URLResolver = new URLResolver
      resolver.setM2compatible(true)
      resolver.setName(r.name)
      resolver.addArtifactPattern(r.pattern)
      resolver
    }
    val ivySettings: IvySettings = new IvySettings
    val chres = new ChainResolver
    chres.setName("default")
    resolvers.foreach { r => chres.add(mkResolver(r)) }
    ivySettings.addResolver(chres)
    ivySettings.setDefaultResolver("default")
    ivySettings.setDefaultIvyUserDir(ivyHome)
    val ivy: Ivy = Ivy.newInstance(ivySettings)
    val ivyFile = File.createTempFile("ivy", ".xml")
    ivyFile.deleteOnExit()
    Files.write(Paths.get(ivyFile.toURI), mkIvyXml(d).getBytes)
    val resolveOptions = new ResolveOptions().setConfs(Array("default", "test", "compile"))
    val report = ivy.resolve(ivyFile.toURI.toURL, resolveOptions)
    ivyFile.delete()
    if (report.getAllProblemMessages.isEmpty && report.getAllArtifactsReports.length > 0) {
      if (d._transitive) {
        report
          .getAllArtifactsReports
          .filter(r => !artifactBlackList.contains(r.getName.replaceAll("_\\d+\\.\\d+$", "")))
          .map(a => ResolvedDependency(d, a.getLocalFile))
      } else {
        val foundArtifact = report.getAllArtifactsReports.find(_.getName == d.artId)
        foundArtifact.map(a=>ResolvedDependency(d, a.getLocalFile)).toList
      }
    }
    else Seq.empty
  }


  private def resolveFast(dep: Dependency): Option[ResolvedDependency] = {
    val file = new File(ivyHome, s"cache/${dep.org}/${dep.artId}/${dep.kind}s/${dep.artId}-${dep.version}.jar")
    if (file.exists())
      Some(ResolvedDependency(dep, file))
    else
      None
  }

  def resolve(dependency: Dependency): Seq[ResolvedDependency] = {
    if (dependency._transitive)  // TODO: parse dependency metadata to lookup local transitive dependencies
      resolveIvy(dependency)
    else
      resolveFast(dependency) match {
        case Some(resolved) => Seq(resolved)
        case None           => resolveIvy(dependency)
      }
  }

  def load(deps: Dependency*)(implicit module: Module): Unit = {
    deps.foreach { d =>
      resolve(d) match {
        case resolved if resolved.nonEmpty =>
          resolved.foreach { res =>
            VfsRootAccess.allowRootAccess(res.file.getCanonicalPath)
            PsiTestUtil.addLibrary(module, res.file.getName, res.file.getParent, res.file.getName)
          }
        case _ => println(s"failed ro resolve dependency: $d")
      }
    }
  }

  def loadAll(implicit module: Module): Unit = load(deps:_*)(module)

  def withResolvers(_resolvers: Seq[Resolver]): DependencyManager = {
    resolvers = resolvers ++ _resolvers
    this
  }
}

object DependencyManager {

  val artifactBlackList = Set("scala-library", "scala-reflect", "scala-compiler")

  def apply(deps: Dependency*): DependencyManager = new DependencyManager(deps:_*)

  object Types extends Enumeration {
    type Type = Value
    val JAR, BUNDLE, SOURCE = Value
  }

  case class Dependency(org: String,
                        artId: String,
                        version: String,
                        conf: String = "compile->default(compile)",
                        _kind: Types.Type = Types.JAR,
                        _transitive: Boolean = false)
  {
    def kind: String = _kind.toString.toLowerCase
    def %(version: String): Dependency = copy(version = version)
    def ^(conf: String): Dependency = copy(conf = conf)
    def %(kind: Types.Type): Dependency = copy(_kind = kind)
    def transitive(): Dependency = copy(_transitive = true)
  }

  case class ResolvedDependency(info: Dependency, file: File)

  implicit class RichStr(value: String) {
    def %(right: String) = Dependency(value, right, "UNKNOWN")
    def %%(right: String)(implicit scalaVersion: ScalaVersion): Dependency =
      Dependency(value, s"${right}_${scalaVersion.major}", "UNKNOWN")
  }

  case class Resolver(name: String, pattern: String)

}

