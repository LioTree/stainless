/* Copyright 2009-2018 EPFL, Lausanne */

package stainless
package verification

import frontend.CallBackWithRegistry
import utils.CheckFilter

import scala.concurrent.Future

import io.circe.Json

/** Callback for verification */
final class VerificationCallBack(override implicit val context: inox.Context)
  extends CallBackWithRegistry with CheckFilter {

  private implicit val debugSection = DebugSectionVerification

  override type Report = VerificationReport
  override val cacheSubDirectory = VerificationComponent.name
  override def parseReportCache(json: Json): Report = VerificationReport.parse(json)

  override def onCycleBegin(): Unit = VerificationComponent.onCycleBegin()

  override def solve(program: Program { val trees: extraction.xlang.trees.type }): Future[Report] = {
    context.reporter.debug(
      s"Verifying the following program: " +
      "\n\tfunctions = [" + (program.symbols.functions.keySet mkString ", ") + "]" +
      "\n\tclasses   = [" + (program.symbols.classes.keySet mkString ", ") + "]"
    )

    VerificationComponent(program, context).map(_.toReport)
  }

}

