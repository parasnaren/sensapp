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
package net.modelbased.sensapp.service.database.raw.backend

import net.modelbased.sensapp.service.database.raw.data._
import net.modelbased.sensapp.library.senml._
import cc.spray.json._
import DataSetProtocols._

/**
 * Trait to define the "interface" a raw backend database must implement
 * @author mosser
 */
abstract trait Backend extends BackendStructure {
  
  /**
   * retrieve the sensor databases stored in this backend
   * @return a list of sensor database identifiers
   */
  def content: List[String]  
  
  /**
   * check if a given sensor database exists
   * @param sensor: the identifier to be checked
   * @param return true if the sensor exists, false else where
   */
  def exists(sensor: String): Boolean  
  
  /**
   * Create a database according to a given creation request
   * @param request the request to be executed
   * @return true if the request was completed, false elsewhere
   */
  def create(request: CreationRequest): Boolean
  
  /**
   * Describe a given sensor database
   * @param sensor the sensor identifier
   * @param prefix the URL prefix associated to the data 
   * @return None if sensor does not exists, a SensorDatabaseDescritpor elsewhere
   */
  def describe(sensor: String, prefix: String): Option[SensorDatabaseDescriptor]
  
  /**
   * __Permanentely__ delete a sensor database
   * @param sensor the sensor identifier to be deleted
   * @return true if the database was successfully deleted, false elsewhere
   * 
   */
  def delete(sensor: String): Boolean
  
  /**
   * push a data set (as a SenML message) into the database
   * @param sensor the sensor identifier to be used
   * @param data the data to be pushed
   * @return a list of ignored data (that are not related to this sensor)
   */
  def push(sensor: String, data: Root): List[MeasurementOrParameter]
  
  /**
   * Retrieve **ALL** the data associated to a given sensor
   * @param sensor the sensor identifier to be used
   * @return a SenML Root object
   */
  def get(sensor: String): Root
  
  /**
   * Retrieve the data associated to a given sensor for a given interval
   * @param sensor the sensor identifier to be used
   * @param from lower bound time stamp (seconds since EPOCH) 
   * @param to upper bound time stamp (seconds since EPOCH) 
   * @return a SenML Root object
   */
  def get(sensor: String, from: Long, to: Long): Root
  
  /**
   * Retrieve the data associated to a set of sensors for a given interval
   * @param sensors the sensor identifiers to be used
   * @param from lower bound time stamp (seconds since EPOCH) 
   * @param to upper bound time stamp (seconds since EPOCH) 
   * @return a SenML Root object
   */
  def get(sensor: Seq[String], from: Long, to: Long): Root
  
  
  /**
   * return the raw database schema associated to a given sensor
   * 
   * schema \in {"Numerical", "String", "Boolean", "Summed", "NumericalStreamChunk"}
   */
  def getSchema(sensor: String): String
  
  /**
   * List of supported schemas
   */
  def schemas: List[String] = RawSchemas.values.toList map { _.toString } 
}



