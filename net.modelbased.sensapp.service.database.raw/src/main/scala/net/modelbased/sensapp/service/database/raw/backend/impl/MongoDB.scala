/**
 * This file is part of SensApp [ http://sensapp.modelbased.net ]
 *
 * Copyright (C) 2012-  SINTEF ICT
 * Contact: Sebastien Mosser <sebastien.mosser@sintef.no>
 *
 * Module: net.modelbased.sensapp.service.database.raw
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
package net.modelbased.sensapp.service.database.raw.backend.impl

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.MongoDBList
import com.mongodb.casbah.Imports._
import net.modelbased.sensapp.service.database.raw.data._
import net.modelbased.sensapp.library.senml.{Root, MeasurementOrParameter}
import net.modelbased.sensapp.service.database.raw.backend._

/**
 * MongoDB Implementation of the Raw Backend trait
 */
class MongoDB extends Backend {
  
  def content: List[String]  = {
    val data = metadata.find(MongoDBObject.empty, MongoDBObject("s" -> 1))
    val it = data map {e => e.getAs[String]("s").get }
    it.toList
  }
  
  def exists(sensor: String): Boolean = {
    metadata.findOne(MongoDBObject("s" -> sensor), MongoDBObject("s" -> 1)) != None
  }
  
  def create(request: CreationRequest): Boolean = {
    val schema = RawSchemas.withName(request.schema)
    val obj = SensorMetaData(request.sensor, request.baseTime, schema)
    metadata += metadata2dbobj(obj)
    true
  }
  
  def describe(sensor: String, prefix: String): Option[SensorDatabaseDescriptor] = {
    metadata.findOne(MongoDBObject("s" -> sensor)) match {
      case None => None
      case Some(dbobj) => {
        val schema = getSchema(sensor)
        val size = data.find(MongoDBObject("s" -> sensor)).size
        val obj = SensorDatabaseDescriptor(sensor, schema, size, prefix+sensor)
        Some(obj)
      }
    }
  }
  
  def delete(sensor: String): Boolean = {
    metadata.findOne(MongoDBObject("s" -> sensor)) match {
      case None => false
      case Some(dbObj) => { 
        metadata -= dbObj; 
        data.find(MongoDBObject("s" -> sensor)) foreach { data -= _ }
        true
      }
    }
  }
  
  def push(sensor: String, root: Root): List[MeasurementOrParameter] = {
    val canon = root.canonized
    val ref = getReferenceTime(sensor)
    canon.measurementsOrParameters match {
      case None => List()
      case Some(lst) => {
        //println(sensor + " " + lst)
        val (accepted, rejected) = lst.par partition { mop => mop.name == Some(sensor) }
        val elements = mop2data(ref, accepted.toList)
        elements.par foreach { data += data2dbobj(sensor, _)  }
        rejected.toList
      }
    }
  }
  
  def get(sensor: String): Root = {
    val sensorMetaData = dbobj2metadata(metadata.findOne(MongoDBObject("s" -> sensor)).get)
    val sensorData = data.find(MongoDBObject("s" -> sensor)).map{ dbobj2data(sensorMetaData.schema,_) }.toList
    buildSenML(sensorMetaData, sensorData)
  }
  
  def get(sensor: String, from: Long, to: Long): Root = {
    val sensorMetaData = dbobj2metadata(metadata.findOne(MongoDBObject("s" -> sensor)).get)
    val shiftedFrom = from - sensorMetaData.timestamp
    val shiftedTo = to - sensorMetaData.timestamp
    val query: DBObject = ("t" $lte shiftedTo $gte shiftedFrom) ++ ("s" -> sensor)
    val sensorData = data.find(query).map{ dbobj2data(sensorMetaData.schema,_) }.toList
    buildSenML(sensorMetaData, sensorData)
  }
  
  def get(sensors: Seq[String], from: Long, to: Long): Root = { 
    val all = sensors.par.map { s => this get(s,from, to) }
    val data = all.map{r => r.canonized.measurementsOrParameters}
          		  .filter{ mop => mop != None }
          		  .map{ _.get }.flatten
    if (data.isEmpty) {
      Root(None, None, None, None, None)
    } else {
      Root(None, None, None, None, Some(data.toList))
    }
    /*
    val sensorMetaData = dbobj2metadata(metadata.findOne(MongoDBObject("s" -> sensor)).get)
    val shiftedFrom = from - sensorMetaData.timestamp
    val shiftedTo = to - sensorMetaData.timestamp
    val query: DBObject = ("t" $lte shiftedTo $gte shiftedFrom) ++ ("s" -> sensor)
    val sensorData = data.find(query).map{ dbobj2data(sensorMetaData.schema,_) }.toList
    buildSenML(sensorMetaData, sensorData) */
  }

  
  def getSchema(sensor: String): String = {
    val obj = metadata.findOne(MongoDBObject("s" -> sensor)).get
    obj.getAs[String]("k").get
  }
  
  /**********************
   ** Private Elements **
   **********************/
  
  def ???(): Nothing = throw new RuntimeException("Not yet implemented") 
  
  private def getReferenceTime(sensor: String): Long = {
    val obj = metadata.findOne(MongoDBObject("s" -> sensor)).get
    obj.getAs[Long]("t").get
  }
  
  private def metadata2dbobj(md: SensorMetaData): MongoDBObject  = { 
    MongoDBObject("s" -> md.name, 
    		      "t" -> md.timestamp, 
    		      "k" -> md.schema.toString) 
  }
  
  private def dbobj2metadata(dbobj: MongoDBObject): SensorMetaData = {
    SensorMetaData(dbobj.getAs[String]("s").get, 
    		       dbobj.getAs[Long]("t").get, 
    		       RawSchemas.withName(dbobj.getAs[String]("k").get))
  }
  
  private def data2dbobj(sensor: String, d: SensorData): MongoDBObject = d match {
    case ne: NumericalData => MongoDBObject("s" -> sensor, 
    										"d" -> ne.data, 
    										"t" -> ne.delta, 
    										"u" -> ne.unit)
    case se: StringData    => MongoDBObject("s" -> sensor, 
    										"d" -> se.data, 
    										"t" -> se.delta, 
    										"u" -> se.unit)
    case be: BooleanData   => MongoDBObject("s" -> sensor, 
    										"d" -> be.data, 
    										"t" -> be.delta)
    case sume: SummedData  => MongoDBObject("s" -> sensor, 
    										"d" -> sume.data,
    										"t" -> sume.delta, 
    										"u" -> sume.unit, 
    										"i" -> sume.instant)
    case strm: NumericalStreamChunkData => ???
  }
  
  private def dbobj2data(schema: RawSchemas.Value, dbobj: MongoDBObject): SensorData = {
    val delta: Long = dbobj.getAs[Long]("t").get
    schema match {
      case RawSchemas.Numerical => NumericalData(delta, dbobj.getAs[Double]("d").get, dbobj.getAs[String]("u").get)
      case RawSchemas.String    => StringData(delta, dbobj.getAs[String]("d").get, dbobj.getAs[String]("u").get)
      case RawSchemas.Boolean   => BooleanData(delta, dbobj.getAs[Boolean]("d").get)
      case RawSchemas.Summed    => SummedData(delta, dbobj.getAs[Double]("d").get, dbobj.getAs[String]("u").get, dbobj.getAs[Option[Double]]("i").get)
      case RawSchemas.NumericalStreamChunk => ???
    }
  }
  
  /*************************
   ** MongoDB Collections **
   *************************/
  private[this] lazy val metadata = mongoConn("sensapp_db")("raw.metadata")
  private[this] lazy val data = mongoConn("sensapp_db")("raw.data")
  private[this] lazy val mongoConn = MongoConnection() 
  
}