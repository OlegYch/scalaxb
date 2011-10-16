/*
 * Copyright (c) 2011 e.e d3si9n
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package scalaxb.compiler.wsdl11

import scala.collection.mutable
import scalaxb.compiler.{Module, Config, Snippet, CustomXML, CanBeWriter}
import scalaxb.{DataRecord}
import wsdl11._
import java.io.{Reader}
import java.net.{URI}
import scala.xml.{Node}
import scalaxb.compiler.xsd.{SchemaLite, SchemaDecl, XsdContext}
import com.weiglewilczek.slf4s.Logger

class Driver extends Module { driver =>
  private lazy val logger = Logger("wsdl")
  type Schema = WsdlPair
  type Context = WsdlContext
  type RawSchema = scala.xml.Node

  val xsddriver = new scalaxb.compiler.xsd.Driver {
    override def verbose = driver.verbose
  }

  def buildContext = WsdlContext()

  def readerToRawSchema(reader: Reader): RawSchema = CustomXML.load(reader)

  def nodeToRawSchema(node: Node) = node

  override def packageName(namespace: Option[String], context: Context): Option[String] =
    xsddriver.packageName(namespace, context.xsdcontext)

  override def processContext(context: Context, cnfg: Config) {
    logger.debug("processContext: " + (context.xsdcontext.schemas.toList map {_.targetNamespace}))
    xsddriver.processContext(context.xsdcontext, cnfg)
    context.definitions foreach {processDefinition(_, context)}
  }

  override def processSchema(schema: Schema, context: Context, cnfg: Config) {}

  def processDefinition(definition: XDefinitionsType, context: Context) {
    val ns = definition.targetNamespace map {_.toString}

    definition.message map { x => context.messages((ns, x.name)) = x }
    definition.portType map { x => context.interfaces((ns, x.name)) = x }
    definition.binding map { x => context.bindings((ns, x.name)) = x }
    definition.service map { x => context.services((ns, x.name)) = x }
  }

  override def generateProtocol(snippet: Snippet,
      context: Context, cnfg: Config): Seq[Node] =
    xsddriver.generateProtocol(snippet, context.xsdcontext, cnfg)

  override def generate(pair: WsdlPair, cntxt: Context, cnfg: Config): Snippet = {
    val ns = (pair.definition, pair.schemas) match {
      case (Some(wsdl), _) => wsdl.targetNamespace map {_.toString}
      case (_, x :: xs) => x.targetNamespace
      case _ => None
    }

    val generator = new GenSource {
      val context = cntxt
      val scope = pair.scope
      val xsdgenerator = new scalaxb.compiler.xsd.GenSource(
        SchemaDecl(targetNamespace = ns, scope = pair.scope),
        cntxt.xsdcontext) {
        val config = cnfg
      }
    }

    val xsdgenerated = pair.schemas map {
      xsddriver.generate(_, cntxt.xsdcontext, cnfg)
    }

    val wsdlgenerated = pair.definition map { wsdl =>
      cntxt.soap11 = !generator.soap11Bindings(wsdl).isEmpty
      generator.generate(wsdl)
    } getOrElse { Snippet(<source></source>) }

    mergeSnippets(xsdgenerated ++ (wsdlgenerated :: Nil))
  }

  override def toImportable(alocation: URI, rawschema: RawSchema): Importable = new Importable {
    import scalaxb.compiler.Module.FileExtension
    import scalaxb.compiler.xsd.{ImportDecl}

    logger.debug("toImportable: " + alocation.toString)
    val location = alocation
    val raw = rawschema
    lazy val (wsdl: Option[XDefinitionsType], xsdRawSchema: Seq[Node]) = alocation.toString match {
      case FileExtension(".wsdl") =>
        val w = scalaxb.fromXML[XDefinitionsType](rawschema)
        val x: Seq[Node] = w.types map { _.any collect {
          case DataRecord(_, _, node: Node) => node
        }} getOrElse {Nil}
        (Some(w), x)
      case FileExtension(".xsd")  =>
        (None, List(rawschema))
    }
    lazy val schemaLite = xsdRawSchema map { SchemaLite.fromXML }
    lazy val targetNamespace = (wsdl, schemaLite) match {
      case (Some(wsdl), _) => wsdl.targetNamespace map {_.toString}
      case (_, x :: xs) => x.targetNamespace
      case _ => None
    }

    lazy val importNamespaces: Seq[String] =
      (wsdl map { wsdl =>
        wsdl.importValue map {_.namespace.toString}
      } getOrElse {Nil}) ++
      (schemaLite flatMap { schemaLite =>
        schemaLite.imports collect {
         case ImportDecl(Some(namespace: String), _) => namespace
        }})

    val importLocations: Seq[String] =
      (wsdl map { wsdl =>
        wsdl.importValue map {_.location.toString}
      } getOrElse {Nil}) ++
      (schemaLite flatMap { schemaLite =>
        schemaLite.imports collect {
         case ImportDecl(_, Some(schemaLocation: String)) => schemaLocation
        }})
    val includeLocations: Seq[String] = schemaLite flatMap { schemaLite =>
      schemaLite.includes map { _.schemaLocation }
    }

    def toSchema(context: Context): WsdlPair = {
      wsdl foreach { wsdl =>
        logger.debug(wsdl.toString)
        context.definitions += wsdl
      }

      val xsd = xsdRawSchema map { x =>
        val schema = SchemaDecl.fromXML(x, context.xsdcontext)
        logger.debug(schema.toString)
        context.xsdcontext.schemas += schema
        schema
      }

      WsdlPair(wsdl, xsd, rawschema.scope)
    }
  }

  def generateRuntimeFiles[To](cntxt: Context)(implicit evTo: CanBeWriter[To]): List[To] =
    List(generateFromResource[To](Some("scalaxb"), "scalaxb.scala", "/scalaxb.scala.template"),
      generateFromResource[To](Some("scalaxb"), "httpclients_dispatch.scala",
        "/httpclients_dispatch.scala.template")) ++
    (if (cntxt.soap11) List(generateFromResource[To](Some("scalaxb"), "soap11.scala", "/soap11.scala.template"),
      generateFromResource[To](Some("soapenvelope11"), "soapenvelope11.scala",
        "/soapenvelope11.scala.template"),
      generateFromResource[To](Some("soapenvelope11"), "soapenvelope11_xmlprotocol.scala",
        "/soapenvelope11_xmlprotocol.scala.template"))
    else List(generateFromResource[To](Some("scalaxb"), "soap.scala", "/soap.scala.template"),
      generateFromResource[To](Some("soapenvelope12"), "soapenvelope12.scala", "/soapenvelope12.scala.template"),
      generateFromResource[To](Some("soapenvelope12"), "soapenvelope12_xmlprotocol.scala",
        "/soapenvelope12_xmlprotocol.scala.template")))
}

case class WsdlPair(definition: Option[XDefinitionsType], schemas: Seq[SchemaDecl], scope: scala.xml.NamespaceBinding)

case class WsdlContext(xsdcontext: XsdContext = XsdContext(),
                       definitions: mutable.ListBuffer[XDefinitionsType] = mutable.ListBuffer(),
                       interfaces:  mutable.ListMap[(Option[String], String), XPortTypeType] = mutable.ListMap(),
                       bindings:    mutable.ListMap[(Option[String], String), XBindingType] = mutable.ListMap(),
                       services:    mutable.ListMap[(Option[String], String), XServiceType] = mutable.ListMap(),
                       faults:      mutable.ListMap[(Option[String], String), XFaultType] = mutable.ListMap(),
                       messages:    mutable.ListMap[(Option[String], String), XMessageType] = mutable.ListMap(),
                       var soap11:  Boolean = false )
