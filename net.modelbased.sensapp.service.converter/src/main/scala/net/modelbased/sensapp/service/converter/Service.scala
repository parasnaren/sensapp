/**
 * This file is part of SensApp [ http://sensapp.modelbased.net ]
 *
 * Copyright (C) 2012-  SINTEF ICT
 * Contact: Sebastien Mosser <sebastien.mosser@sintef.no>
 *
 * Module: net.modelbased.sensapp.service.converter
 *
 * SensApp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * SensApp is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General
 * Public License along with SensApp. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package net.modelbased.sensapp.service.converter


import au.com.bytecode.opencsv.CSVReader
import cc.spray.http._
import cc.spray.json._
import cc.spray.json.DefaultJsonProtocol._
import cc.spray.directives._
import cc.spray.typeconversion.SprayJsonSupport
import cc.spray.io.IoWorker
import cc.spray.client.HttpConduit
import cc.spray.can.client.HttpClient
import cc.spray.RequestContext

import java.io.StringReader
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.text.NumberFormat
import net.modelbased.sensapp.library.system.{Service => SensAppService, URLHandler}
import net.modelbased.sensapp.library.senml.{MeasurementOrParameter, Root, DataType, SumDataValue, DoubleDataValue, StringDataValue, BooleanDataValue}
import net.modelbased.sensapp.library.senml.export.JsonParser
import net.modelbased.sensapp.service.converter.request._
import net.modelbased.sensapp.service.converter.request.CSVDescriptorProtocols._
import net.modelbased.sensapp.service.converter.request.CSVExportDescriptorProtocols._
import scala.collection.mutable.Buffer
import scala.collection.JavaConversions._
import scala.collection.mutable.StringBuilder


import akka.actor._
import akka.dispatch._
import akka.util.duration._



trait Service extends SensAppService {
  
  override implicit val partnerName = "converter"
    
  implicit val system = ActorSystem()
  val ioWorker = new IoWorker(system).start()
  def httpClientName = "converter-client"
  val httpClient = system.actorOf(
    props = Props(new HttpClient(ioWorker)),
    name = httpClientName
  )
    
  private[this] val _registry = new CSVDescriptorRegistry()
  
  private def ifExists(context: RequestContext, name: String, lambda: => Unit) = {
    if (_registry exists ("desc", name))
      lambda
    else
      context fail(StatusCodes.NotFound, "Unknown descriptor [" + name + "]") 
  }
    
  
  val service = {
    path("converter" / "toCSV") {
      post {
        content(as[CSVExportDescriptor]) { exportDesc => context =>
          val csv = generateCSV(exportDesc)
          context complete csv
        } 
      } ~ cors("POST")
    } ~
    path("converter" / "fromCSV") {
      post {
        content(as[CSVDescriptor]) { desc => context =>
          if (_registry exists ("name", desc.name)){
            context fail (StatusCodes.Conflict, "A CSV descriptor identified by ["+ desc.name +"] already exists!")
          } else {
            _registry push desc
            context complete (StatusCodes.Created, URLHandler.build("/converter/fromCSV/" + desc.name))
          }
        }
      } ~
      get { 
        parameter("flatten" ? false) { flatten =>  context =>
          val data = (_registry retrieve(List()))
          if (flatten) {
            context complete data
          } else {
            context complete (data map { r => URLHandler.build("/converter/fromCSV/" + r.name) })
          }
        }
      } ~ cors("GET", "POST")
    } ~
    path("converter" / "fromCSV" / "[a-zA-Z0-9][a-zA-Z0-9-:._/\\[\\]]*".r) { name =>
      get { context =>
        ifExists(context, name, {context complete (_registry pull ("desc", name)).get})
      } ~
      delete { context =>
        ifExists(context, name, {  
          val desc = (_registry pull ("desc", name)).get
          _registry drop desc 
          context complete "true"
        })
      } ~
      post { 
        content(as[String]) { rawData => context =>
          ifExists(context, name, {
            val desc = (_registry pull ("desc", name)).get
            val status = parseCSV(desc, rawData)
            context complete status
          })
        }
      } ~ 
      put {
        content(as[CSVDescriptor]) { desc => context => 
          if (desc.name != name) {
            context fail(StatusCodes.Conflict, "Request content does not match URL for update")
          } else {
            ifExists(context, name, { _registry push(desc); context complete desc })
	      } 
        }
      } ~ cors("GET", "PUT", "POST", "DELETE")
    }
  }
  
  def generateCSV(exportDesc : CSVExportDescriptor) : String = {
    val groupedDS : Map[String, List[DataSetDescriptor]] = exportDesc.datasets.groupBy{dataset => 
      val url = URLHandler.extract(dataset.url)
      val host = url._1._1 + ":" + url._1._2
      host
    }
    val roots : List[Root] = groupedDS.par.flatMap{case (host, datasets) =>
      val conduit = new HttpConduit(httpClient, host)
      datasets.par.map{dataset => 
        val url = URLHandler.extract(dataset.url)
        val responseFuture = conduit.sendReceive(HttpRequest(HttpMethods.GET, uri = url._2))
        try {
          Await.result(responseFuture, 4 second)
          Some(JsonParser.fromJson(responseFuture().content.get.toString))
        } catch {case _ => None}
      }
    }.seq.toList.flatten
    
    
    val canonized = roots.par.map(_.canonized).seq.collect{case root => root.measurementsOrParameters}.toList.flatten.flatten
    val index : Map[Int, String] = canonized.groupBy(mop => mop.name).keySet.flatten.zipWithIndex.toMap.map(_.swap)
    
    val builder : StringBuilder = new StringBuilder()
    
    //TODO: append headers
    
    //TODO: manage unroll strategy by distributing string values evenly between two timestamps
    canonized.groupBy(mop => mop.time.getOrElse(-1l)).filterKeys(k=> k > -1).map{case (t, mops) => 
    	builder append (t*1000) //we export timestamps in ms
    	for(i <- 0 to index.keys.last){
    	  builder append exportDesc.separator
    	  val lastIndex = mops.lastIndexOf(index(i))
    	  if (lastIndex > -1) {
    	    builder.append(mops.get(lastIndex).data match {
              case DoubleDataValue(d)   => d
              case StringDataValue(s)  => s
              case BooleanDataValue(b) => b
              case SumDataValue(d,i)   => d
              case _ => "-"
            })
    	  } else {
    	    builder append "-"
    	  }
    	}
    	builder append "\n"
    }
    builder.toString
  }

  
  
  def parseCSV(request : CSVDescriptor, rawData : String) : Root = {
    val start = System.currentTimeMillis
    
    
    val separator = request.separator.getOrElse(',')
    val escape = request.escape.getOrElse('\\')
    
    val reader : CSVReader = new CSVReader(new StringReader(rawData), separator, escape, 0);
    val myEntries = reader.readAll();
    
    
    val getTimestamp: String => Option[Long] = (s : String) => try { 
      request.timestamp.format match {
        case None =>  
          val t = s.toLong / 1000
          Some(t)
        case Some(format) => {
          val dateFormat = new SimpleDateFormat(format.pattern, new Locale(format.locale))
          val t = dateFormat.parse(s).getTime() / 1000
          Some(t)
        }
      } 
    } catch { case _ =>
      println("Cannot extract timestamp from " + s)
      None 
    }
    
    val extractDouble: (String, String) => Option[Double] = (data, col : String) => try {
      val cleanSplit = data.trim.split("^(0+)(\\d+.\\d+)")//removes leading zeros (e.g. as generated in Torque files) preventing proper parsing
      val clean = (if (cleanSplit.length > 2) cleanSplit(2) else data.trim)
      val cleanDouble = if (request.locale.isDefined) {
        val format = NumberFormat.getInstance(new Locale(request.locale.get));
    	format.parse(clean).doubleValue()
      } else {
        clean.toDouble
      }
      Some(cleanDouble)
    } catch { case _ =>
        println("Cannot parse value " + data +", ignoring measurement " + col)
        None
    }
    
    //CSV file grouped by (parseable) timestamps
    val chunks : Map[Long, Buffer[Array[String]]] = myEntries.groupBy(line => getTimestamp(line(request.timestamp.columnId).trim).getOrElse(-1l)).filterKeys(k => k>0)
    
    val extract: (List[Array[String]], Long) => List[MeasurementOrParameter] = (chunk : List[Array[String]], timestamp : Long) => request.columns.flatMap{col =>
      if (col.strategy.isDefined)
    	  col.strategy.get match {
    	  	case "chunk" => Some(MeasurementOrParameter(Some(col.name), Some(col.unit), None, Some(chunk.collect{case cols => cols(col.columnId)}.mkString(", ")), None, None, Some(timestamp), None))
            case strategy : String =>
              val values = chunk.flatMap{line =>
                val data = line(col.columnId)
                extractDouble(data, col.name)
              }
              strategy match {
                case "min" => Some(MeasurementOrParameter(Some(col.name), Some(col.unit), Some(values.sortWith(_ < _).head), None, None, None, Some(timestamp), None))
                case "max" => Some(MeasurementOrParameter(Some(col.name), Some(col.unit), Some(values.sortWith(_ > _).head), None, None, None, Some(timestamp), None))
                case "avg" => Some(MeasurementOrParameter(Some(col.name), Some(col.unit), Some((values.sum)/values.size), None, None, None, Some(timestamp), None))
                case "one" => Some(MeasurementOrParameter(Some(col.name), Some(col.unit), Some(values.head), None, None, None, Some(timestamp), None))
            }
          }
      else {//
        col.kind match {
          case "number" => extractDouble(chunk.head(col.columnId), col.name).flatMap(number => Some(MeasurementOrParameter(Some(col.name), Some(col.unit), Some(number), None, None, None, Some(timestamp), None)))
          case "string" =>  Some(MeasurementOrParameter(Some(col.name), Some(col.unit), None, Some(chunk.collect{case cols => cols(col.columnId)}.mkString(";")), None, None, Some(timestamp), None))
          case "boolean" =>  Some(MeasurementOrParameter(Some(col.name), Some(col.unit), None, None, Some(chunk.head(col.columnId).trim=="true"), None, Some(timestamp), None))
          case "sum" => extractDouble(chunk.head(col.columnId), col.name).flatMap(number => Some(MeasurementOrParameter(Some(col.name), Some(col.unit), None, None, None, Some(number), Some(timestamp), None)))
          case _ => 
            println("Kind " + col.kind + " does not exist, ignoring measurement " + col.name)
            None
        }
      }
    }
    
     val raw = chunks/*.par*/.flatMap{ case (timestamp, lines) => extract(lines.toList, timestamp) }.toIndexedSeq.sortWith(_.time.getOrElse(0l) < _.time.getOrElse(0l))
    
    println("Creating Root with " + raw.seq.size + " elements...")
    val root = Root(Some(request.name + "/" + UUID.randomUUID()), None, None, None, Some(raw/*.seq*/))
    
    val stop = System.currentTimeMillis
    println("Parsing CSV took " + (stop-start) + " ms")
    
    root
  }  
}