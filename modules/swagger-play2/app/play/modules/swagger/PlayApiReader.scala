/**
 *  Copyright 2012 Wordnik, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package play.modules.swagger

import com.wordnik.swagger.core._
import com.wordnik.swagger.annotations._
import com.wordnik.swagger.core.ApiValues._
import com.wordnik.swagger.core.util.ReflectionUtil

import play.router.SwaggerRoutesCompiler.Route

import javax.ws.rs._
import javax.ws.rs.core.Context
import java.lang.annotation.Annotation

import java.lang.reflect.{ Type, Field, Modifier, Method }

import play.api.Play.current
import play.api.Logger

import play.router.DynamicPart
import play.router.StaticPart

import scala.collection.JavaConversions._
import scala.io.Source

object PlayApiReader {
  import scalax.file.Path
  import java.io.File
  import play.core.Router._

  private val endpointsCache = scala.collection.mutable.Map.empty[Class[_], Documentation]
  private var _routesCache: Map[String, Route] = null
  var formatString = current.configuration.getString("swagger.api.format.string") match {
    case Some(str) => str
    case _ => ".{format}"
  }

  def setFormatString(str: String) = {
    if (formatString != str) {
      endpointsCache.clear
      formatString = str
    }
  }

  def read(hostClass: Class[_], apiVersion: String, swaggerVersion: String, basePath: String, apiPath: String): Documentation = {
    endpointsCache.get(hostClass) match {
      case None => {
        val doc = new PlayApiSpecParser(hostClass, apiVersion, swaggerVersion, basePath, apiPath).parse
        endpointsCache += hostClass -> doc.clone.asInstanceOf[Documentation]
        doc
      }
      case Some(doc) => doc.clone.asInstanceOf[Documentation]
      case _ => null
    }
  }

  def routesCache = {
    if (_routesCache == null) _routesCache = populateRoutesCache
    _routesCache
  }

  def clear {
    _routesCache = null
    endpointsCache.clear
  }

  /*
  def pathFromRoute(pathString: String, method: String) = {
    pathString.split()
  }*/

  private def populateRoutesCache: Map[String, Route] = {
    val classLoader = this.getClass.getClassLoader
    val routesStream = classLoader.getResourceAsStream("routes")
    val routesString = Source.fromInputStream(routesStream).getLines().mkString("\n")

    import play.router.SwaggerRoutesCompiler._

    val p = new RouteFileParser()
    val parsedRoutes = p.parse(routesString)

    parsedRoutes.successful match {
      case true => {
        parsedRoutes.get.map{rule => {
          rule match {
            case route @ Route(_, _, _, _) =>
              val routeName = route.call.packageName + "." + route.call.controller + "$." + route.call.method
              (routeName, route)
            case x @ _ =>
              throw new Exception("Rule type not yet supported: " + x)
          }
        }}.toMap
      }
      case _ => Map[String, Route]()
    }
  }
}

/**
 * Reads swaggers annotations, play route information and uses reflection to build API information on a given class
 */
private class PlayApiSpecParser(_hostClass: Class[_], _apiVersion: String, _swaggerVersion: String, _basePath: String, _resourcePath: String)
  extends ApiSpecParserTrait {

  override def hostClass = _hostClass
  override def apiVersion = _apiVersion
  override def swaggerVersion = _swaggerVersion
  override def basePath = _basePath
  override def resourcePath = _resourcePath

  val documentation = new Documentation
  val apiEndpoint = hostClass.getAnnotation(classOf[Api])

  val LIST_RESOURCES_PATH = "/resources"

  def setFormatString(str: String) = {
    Logger debug ("setting format string")
    if (PlayApiReader.formatString != str) {
      Logger debug ("clearing endpoint cache")
      PlayApiReader.formatString = str
    }
  }

  /**
   * Get the path for a given method
   */
  override def getPath(method: Method) = {
    val fullMethodName = getFullMethodName(method)
    val lookup = PlayApiReader.routesCache.get(fullMethodName)

    val str = "/" + (lookup match {
      case Some(route) => route.path.parts map {
        part =>
          part match {
            case DynamicPart(name, _) => "{" + name + "}"
            case StaticPart(name) => name
          }
      } mkString
      case None => Logger error "Cannot determine Path. Nothing defined in play routes file for api method " + method.toString; this.resourcePath
    })

    val s = PlayApiReader.formatString match {
      case "" => str
      case e: String => str.replaceAll(".json", PlayApiReader.formatString).replaceAll(".xml", PlayApiReader.formatString)
    }
    Logger debug (s)
    s
  }

  def getFullMethodName(method: Method): String = {
    hostClass.getCanonicalName.indexOf("$") match {
      case -1 => hostClass.getCanonicalName + "$." + method.getName
      case _ => hostClass.getCanonicalName + "." + method.getName
    }
  }

  override protected def processOperation(method: Method, o: DocumentationOperation) = {
    val fullMethodName = getFullMethodName(method)
    val lookup = PlayApiReader.routesCache.get(fullMethodName)
    lookup match {
      case Some(route) => o.httpMethod = route.verb.value
      case None => Logger error "Could not find route " + fullMethodName
    }
    o
  }

  override def processParamAnnotations(docParam: DocumentationParameter, paramAnnotations: Array[Annotation], method: Method): Boolean = {
    var ignoreParam = false
    for (pa <- paramAnnotations) {
      pa match {
        case apiParam: ApiParam => parseApiParam(docParam, apiParam, method)
        case wsParam: QueryParam => {
          docParam.name = readString(wsParam.value, docParam.name)
          docParam.paramType = readString(TYPE_QUERY, docParam.paramType)
        }
        case wsParam: PathParam => {
          docParam.name = readString(wsParam.value, docParam.name)
          docParam.required = true
          docParam.paramType = readString(TYPE_PATH, docParam.paramType)
        }
        case wsParam: MatrixParam => {
          docParam.name = readString(wsParam.value, docParam.name)
          docParam.paramType = readString(TYPE_MATRIX, docParam.paramType)
        }
        case wsParam: HeaderParam => {
          docParam.name = readString(wsParam.value, docParam.name)
          docParam.paramType = readString(TYPE_HEADER, docParam.paramType)
        }
        case wsParam: FormParam => {
          docParam.name = readString(wsParam.value, docParam.name)
          docParam.paramType = readString(TYPE_FORM, docParam.paramType)
        }
        case wsParam: CookieParam => {
          docParam.name = readString(wsParam.value, docParam.name)
          docParam.paramType = readString(TYPE_COOKIE, docParam.paramType)
        }
        case wsParam: Context => ignoreParam = true
        case _ => Unit
      }
    }
    ignoreParam
  }

  override def parseHttpMethod(method: Method, apiOperation: ApiOperation): String = {
    if (apiOperation.httpMethod() != null && apiOperation.httpMethod().trim().length() > 0)
      apiOperation.httpMethod().trim()
    else {
      val wsGet = method.getAnnotation(classOf[javax.ws.rs.GET])
      val wsDelete = method.getAnnotation(classOf[javax.ws.rs.DELETE])
      val wsPost = method.getAnnotation(classOf[javax.ws.rs.POST])
      val wsPut = method.getAnnotation(classOf[javax.ws.rs.PUT])
      val wsHead = method.getAnnotation(classOf[javax.ws.rs.HEAD])

      if (wsGet != null) ApiMethodType.GET
      else if (wsDelete != null) ApiMethodType.DELETE
      else if (wsPost != null) ApiMethodType.POST
      else if (wsPut != null) ApiMethodType.PUT
      else if (wsHead != null) ApiMethodType.HEAD
      else null
    }
  }
}
