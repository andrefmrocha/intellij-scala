package org.jetbrains.plugins.scala.compilation

import java.util.concurrent.TimeUnit

import com.intellij.compiler.CompilerWorkspaceConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.CompilerTester
import org.jetbrains.plugins.scala.{LatestScalaVersions, ScalaVersion}
import org.jetbrains.plugins.scala.base.ScalaSdkOwner
import org.jetbrains.plugins.scala.base.libraryLoaders.LibraryLoader
import org.jetbrains.plugins.scala.performance.DownloadingAndImportingTestCase
import org.jetbrains.plugins.scala.compilation.CompilerTestUtil.RevertableChange
import org.jetbrains.plugins.scala.compiler.{CompileServerLauncher, ScalaCompileServerSettings}
import org.jetbrains.plugins.scala.debugger.ScalaCompilerTestBase
import org.jetbrains.plugins.scala.debugger.ScalaCompilerTestBase.ListCompilerMessageExt
import org.jetbrains.plugins.scala.extensions.inWriteAction
import org.jetbrains.plugins.scala.project.ProjectExt
import org.jetbrains.plugins.scala.util.Metering._

import scala.collection.JavaConverters.seqAsJavaListConverter

// TODO ignore these tests?
abstract class CompilationBenchmark
  extends DownloadingAndImportingTestCase
    with ScalaSdkOwner {

  override protected def librariesLoaders: Seq[LibraryLoader] = Seq.empty

  override protected def supportedIn(version: ScalaVersion): Boolean = version == LatestScalaVersions.Scala_2_13

  private var revertable: RevertableChange = _
  private var compiler: CompilerTester = _

  override def setUp(): Unit = {
    super.setUp()
    revertable = CompilerTestUtil.withEnabledCompileServer(true)
    revertable.apply()
    compiler = new CompilerTester(myProject, myProject.modules.asJava, null)
  }

  override def tearDown(): Unit = try {
    compiler.tearDown()
    ScalaCompilerTestBase.stopAndWait()
    val table = ProjectJdkTable.getInstance
    inWriteAction {
      table.getAllJdks.foreach(table.removeJdk)
    }
  } finally {
    compiler = null
    revertable.revert()
    super.tearDown()
  }

  // All benchmark parameters
  private final val Repeats = 1
  private final val HeapSizeValues = Seq(2048, 4096)
  private final val CompileInParallelValues = Seq(false, true)
  private final val PossibleJvmOptions = Seq(
    Seq("-server", ""),
    Seq("-Xss1m", ""),
    Seq("-XX:+UseParallelGC", ""),
    Seq("-XX:MaxInlineLevel=20", "-XX:MaxInlineLevel=40", "")
  )

  private def jvmOptionsIterator: Iterator[String] =
    cartesianProduct(PossibleJvmOptions)
      .map(_.filter(_.nonEmpty).sorted.mkString(" "))
      .iterator

  private def cartesianProduct[T](seqs: Seq[Seq[T]]): Seq[Seq[T]] = {
    val initialValue: Seq[Seq[T]] = Seq(Seq.empty)
    seqs.foldLeft(initialValue) { (acc, seq) =>
      acc.flatMap(r => seq.map(x => r ++ Seq(x)))
    }
  }

  private implicit val scaleConfig: ScaleConfig = ScaleConfig(1, TimeUnit.SECONDS)

  private var results: Map[Params, Double] = Map.empty

  private case class Params(compileInParallel: Boolean,
                            heapSize: Int,
                            jvmOptions: String)

  private def benchmark(params: Params): Unit = {
    val Params(compileInParallel, heapSize, jvmOptions) = params

    CompilerWorkspaceConfiguration.getInstance(myProject).PARALLEL_COMPILATION = compileInParallel
    val settings = ScalaCompileServerSettings.getInstance
    settings.COMPILE_SERVER_MAXIMUM_HEAP_SIZE = heapSize.toString
    settings.COMPILE_SERVER_JVM_PARAMETERS = jvmOptions

    ApplicationManager.getApplication.saveSettings()
    CompileServerLauncher.stop(timeoutMs = 3000)
    CompileServerLauncher.ensureServerRunning(myProject)

    implicit val printReportHandler: MeteringHandler = { (time: Double, _) =>
      results += params -> time
    }

    benchmarked(Repeats) {
      compiler.rebuild().assertNoProblems(allowWarnings = true)
    }
  }

  def testBenchmark(): Unit = {
    val paramsList = for {
      compileInParallel <- CompileInParallelValues
      heapSize <- HeapSizeValues
      jvmOptions <- jvmOptionsIterator
    } yield Params(
      compileInParallel = compileInParallel,
      heapSize = heapSize,
      jvmOptions = jvmOptions
    )

    println(
      s"""|==================Benchmark Report==================
          |OS:      ${SystemInfo.OS_NAME} (${SystemInfo.OS_VERSION}, ${SystemInfo.OS_ARCH})
          |Project: $githubRepoUrl
          |Repeats: $Repeats""".stripMargin
    )

    for (params <- paramsList)
      benchmark(params)

    results.toSeq.sortBy(_._2).foreach { case (Params(compileInParallel, heapSize, jvmOptions), time) =>
      val paramsStr = s"compileInParallel=$compileInParallel;heapSize=$heapSize;jvmOptions=$jvmOptions"
      val timeStr = s"$time ${scaleConfig.unit}"
      println(s"$paramsStr => $timeStr")
    }
  }
}

class ZioCompilationBenchmark
  extends CompilationBenchmark {

  override def githubUsername: String = "zio"

  override def githubRepoName: String = "zio"

  override def revision: String = "dd21e98ead466bfef5d63e84a77b115122296146"
}
