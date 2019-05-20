/* Copyright 2009-2019 EPFL, Lausanne */

package stainless

import inox.{Reporter, OptionDef, OptionValue}

import java.io.File
import java.nio.file.{FileSystems, Path}

import com.typesafe.config.{ConfigFactory, ConfigValue, ConfigValueType, ConfigException}

object Configuration {

  import scala.collection.JavaConverters._

  val ConfigName: String = "stainless.conf"

  private def isConfigFile(file: File): Boolean = {
    file.getName == ConfigName ||
    file.getName == s".$ConfigName"
  }

  def findConfigFile(): Option[File] = {
    RecursiveFileFinder.find(isConfigFile(_))
  }

  def parseDefault(options: Seq[OptionDef[_]])(implicit reporter: Reporter): Seq[OptionValue[_]] = {
    findConfigFile() map { file =>
      parse(file, options)
    } getOrElse Seq.empty
  }

  def parse(file: File, options: Seq[OptionDef[_]])(implicit reporter: Reporter): Seq[OptionValue[_]] = try {
    val conf = ConfigFactory.parseFile(file)
    val entries = asScalaSet(conf.entrySet).map { entry =>
      entry.getKey -> convert(entry.getKey, entry.getValue)
    }.toMap

    val optDefMap = options.groupBy(_.name).mapValues(_.head)

    val optValues = entries map { case (name, str) =>
      optDefMap.get(name) map { optDef =>
        optDef.parse(str)
      } getOrElse {
        reporter.fatalError(s"Unknown option: $name")
      }
    }

    optValues.toSeq
  } catch {
    case e: ConfigException =>
      reporter.error(s"Invalid configuration file at '$file': ${e.getMessage}")
      Seq.empty
  }

  private def convert(name: String, config: ConfigValue)(implicit reporter: Reporter): String = {
    val unwrapped = config.unwrapped

    config.valueType match {
      case ConfigValueType.BOOLEAN => unwrapped.toString
      case ConfigValueType.NUMBER => unwrapped.toString
      case ConfigValueType.STRING => unwrapped.toString
      case ConfigValueType.LIST =>
        val values = asScalaIterator(unwrapped.asInstanceOf[java.util.List[Any]].iterator).toList
        values.map(_.toString).mkString(",")
      case _ =>
        reporter.fatalError(s"Unsupported option type for option '$name': $config")
    }
  }
}

object RecursiveFileFinder {
  import scala.collection.JavaConverters._

  def currentDirectory(): File = {
    FileSystems.getDefault().getPath(".").normalize.toAbsolutePath().toFile
  }

  def find(pred: File => Boolean): Option[File] = {
    findIn(pred, currentDirectory())
  }

  def findIn(pred: File => Boolean, directory: File): Option[File] = {
    findWithin(pred, directory) orElse {
      val parent = Option(directory.toPath.getParent).map(_.toFile)
      parent flatMap (p => findIn(pred, p))
    }
  }

  private def findWithin(pred: File => Boolean, directory: File): Option[File] = {
    directory.listFiles().toList
      .filter(_.isFile)
      .find(pred)
  }
}
