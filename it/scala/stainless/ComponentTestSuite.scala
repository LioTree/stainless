/* Copyright 2009-2016 EPFL, Lausanne */

package stainless

import scala.concurrent.duration._

trait ComponentTestSuite extends inox.TestSuite with inox.ResourceUtils {

  val component: SimpleComponent

  override def configurations: Seq[Seq[inox.OptionValue[_]]] = Seq(
    Seq(inox.optSelectedSolvers(Set("smt-z3")), inox.optTimeout(150.seconds))
  )

  def extract(files: Seq[String]): (Seq[(String, Seq[Identifier])], Program { val trees: component.trees.type }) = {
    val reporter = new inox.TestSilentReporter

    val ctx = inox.Context(reporter, new inox.utils.InterruptManager(reporter))
    val (structure, program) = frontends.scalac.ScalaCompiler(ctx, Build.libraryFiles ++ files.toList)
    val exProgram = component.extract(program)

    assert(reporter.lastErrors.isEmpty)

    (for (u <- structure if u.isMain) yield {
      val unitFuns = u.allFunctions(program.symbols)
      val allFuns = inox.utils.fixpoint { (funs: Set[Identifier]) =>
        funs ++ exProgram.symbols.functions.values.flatMap { fd =>
          val source = fd.flags.collect { case component.trees.Derived(id) => id }.toSet
          if ((source intersect funs).nonEmpty) {
            Some(fd.id)
          } else {
            None
          }
        }
      } (unitFuns.toSet)
      (u.id.name, allFuns.toSeq)
    }, exProgram)
  }

  val ignored: Set[String] = Set.empty

  def testAll(dir: String)(block: (component.Report, inox.Reporter) => Unit): Unit = {
    val fs = resourceFiles(s"regression/$dir", _.endsWith(".scala")).toList

    val (funss, program) = extract(fs.map(_.getPath))

    for ((name, funs) <- funss) {
      if (ignored(s"$dir/$name")) {
        ignore(s"$dir/$name")(_ => ())
      } else {
        test(s"$dir/$name") { ctx =>
          val newProgram = program.withContext(ctx)
          val report = component.apply(funs, newProgram)
          block(report, ctx.reporter)
        }
      }
    }
  }
}