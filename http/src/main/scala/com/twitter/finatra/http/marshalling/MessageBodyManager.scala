package com.twitter.finatra.http.marshalling

import com.google.inject.internal.MoreTypes.ParameterizedTypeImpl
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.annotations.{MessageBodyWriter => MessageBodyWriterAnnotation}
import com.twitter.inject.Injector
import com.twitter.inject.TypeUtils.singleTypeParam
import com.twitter.inject.conversions.map._
import java.lang.annotation.Annotation
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import net.codingwell.scalaguice._
import scala.collection.mutable

/**
 * Manages registration of message body components. I.e., components that specify how to parse
 * an incoming Finagle HTTP request body into a model object ("message body reader") and how to
 * render a given type as a response ("message body writer").
 *
 * A default implementation for both a reader and a writer is necessary in order to specify the
 * behavior to invoke when a reader or writer is not found for a requested type `T`. The framework
 * binds two default implementations: [[DefaultMessageBodyReader]] and [[DefaultMessageBodyWriter]] via
 * the [[com.twitter.finatra.http.modules.MessageBodyModule]].
 *
 * These defaults are overridable by providing a customized `MessageBodyModule` in your
 * [[com.twitter.finatra.http.HttpServer]] by overriding the [[com.twitter.finatra.http.HttpServer.messageBodyModule]].
 *
 * When the [[MessageBodyManager]] is obtained from the injector (which is configured with the framework
 * [[com.twitter.finatra.http.modules.MessageBodyModule]]) the framework default implementations for
 * the reader and writer will be provided accordingly (along with the configured server injector).
 *
 * @param injector the configured [[com.twitter.inject.Injector]] for the server.
 * @param defaultMessageBodyReader a default message body reader implementation.
 * @param defaultMessageBodyWriter a default message body writer implementation.
 */
@Singleton
class MessageBodyManager @Inject()(
  injector: Injector,
  defaultMessageBodyReader: DefaultMessageBodyReader,
  defaultMessageBodyWriter: DefaultMessageBodyWriter
) {

  private[this] val classTypeToReader = mutable.Map[Type, MessageBodyReader[Any]]()
  private[this] val classTypeToWriter = mutable.Map[Type, MessageBodyWriter[Any]]()

  private[this] val annotationTypeToWriter = mutable.Map[Type, MessageBodyWriter[Any]]()

  private[this] val readerCache =
    new ConcurrentHashMap[Manifest[_], Option[MessageBodyReader[Any]]]()
  private[this] val writerCache = new ConcurrentHashMap[Any, MessageBodyWriter[Any]]()

  /* Public (Config methods called during server startup) */

  final def add[MBC <: MessageBodyComponent: Manifest](): Unit = {
    val componentSupertypeClass =
      if (classOf[MessageBodyReader[_]].isAssignableFrom(manifest[MBC].runtimeClass))
        classOf[MessageBodyReader[_]]
      else
        classOf[MessageBodyWriter[_]]

    val componentSupertypeType = typeLiteral.getSupertype(componentSupertypeClass).getType

    add[MBC](singleTypeParam(componentSupertypeType))
  }

  final def add[MBC <: MessageBodyComponent: Manifest](reader: MBC): Unit = {
    val componentSupertypeType =
      typeLiteral[MBC].getSupertype(classOf[MessageBodyReader[_]]).getType
    addComponent(reader, singleTypeParam(componentSupertypeType))
  }

  final def addByAnnotation[Ann <: Annotation: Manifest, T <: MessageBodyWriter[_]: Manifest](): Unit = {
    val messageBodyWriter = injector.instance[T]
    val annotation = manifest[Ann].runtimeClass.asInstanceOf[Class[Ann]]
    val requiredAnnotationClazz = classOf[MessageBodyWriterAnnotation]
    assert(
      annotation.isAnnotationPresent(requiredAnnotationClazz),
      s"The annotation: ${annotation.getSimpleName} is not annotated with the required ${requiredAnnotationClazz.getName} annotation.")
    annotationTypeToWriter(annotation) = messageBodyWriter.asInstanceOf[MessageBodyWriter[Any]]
  }

  final def addByComponentType[M <: MessageBodyComponent: Manifest, T <: MessageBodyWriter[_]: Manifest](): Unit = {
    val messageBodyWriter = injector.instance[T]
    val componentType = manifest[M].runtimeClass.asInstanceOf[Class[M]]
    writerCache.putIfAbsent(componentType, messageBodyWriter.asInstanceOf[MessageBodyWriter[Any]])
  }

  final def addExplicit[MBC <: MessageBodyComponent: Manifest, TypeToReadOrWrite: Manifest](): Unit = {
    add[MBC](typeLiteral[TypeToReadOrWrite].getType)
  }

  /* Public (Per-request read and write methods) */

  final def read[T: Manifest](request: Request): T = {
    val requestManifest = manifest[T]
    readerCache.atomicGetOrElseUpdate(requestManifest, {
      val objType = typeLiteral(requestManifest).getType
      classTypeToReader.get(objType).orElse(findReaderBySuperType(objType))
    }) match {
      case Some(reader) =>
        reader.parse(request).asInstanceOf[T]
      case _ =>
        defaultMessageBodyReader.parse[T](request)
    }
  }

  /* Note: writerCache is bounded on the number of unique classes returned from controller routes */
  final def writer(obj: Any): MessageBodyWriter[Any] = {
    val objClass = obj.getClass
    writerCache.atomicGetOrElseUpdate(objClass, {
      classTypeToWriter
        .get(objClass)
        .orElse(classAnnotationToWriter(objClass))
        .getOrElse(defaultMessageBodyWriter)
    })
  }

  /* Private */

  private[this] def findReaderBySuperType(t: Type): Option[MessageBodyReader[Any]] = {
    val classTypeToReaderOption: Option[(Type, MessageBodyReader[Any])] = t match {
      case _ : ParameterizedTypeImpl =>
        None // User registered MessageBodyComponents with parameterized types are not supported, so do not attempt to look up a reader
      case _ =>
        classTypeToReader
          .find { case (tpe, _) =>
            tpe.asInstanceOf[Class[_]]
              .isAssignableFrom(t.asInstanceOf[Class[_]])
          }
    }
    classTypeToReaderOption.map { case (_, messageBodyReader) => messageBodyReader }
  }

  private[this] def add[MessageBodyComp: Manifest](typeToReadOrWrite: Type): Unit = {
    typeToReadOrWrite match {
      case _: ParameterizedTypeImpl =>
        throw new IllegalArgumentException("Adding a message body component with parameterized types, e.g. MessageBodyReader[Map[String, String]] is not supported.")
      case _ =>
        val messageBodyComponent = injector.instance[MessageBodyComp]
        addComponent(messageBodyComponent, typeToReadOrWrite)
    }
  }

  private[this] def addComponent(messageBodyComponent: Any, typeToReadOrWrite: Type): Unit = {
    messageBodyComponent match {
      case reader: MessageBodyReader[_] =>
        classTypeToReader(typeToReadOrWrite) = reader.asInstanceOf[MessageBodyReader[Any]]
      case writer: MessageBodyWriter[_] =>
        classTypeToWriter(typeToReadOrWrite) = writer.asInstanceOf[MessageBodyWriter[Any]]
    }
  }

  private[this] def classAnnotationToWriter(clazz: Class[_]): Option[MessageBodyWriter[Any]] = {
    // we stop at the first supported annotation for looking up a writer
    clazz
      .getAnnotations
      .collectFirst(isRequiredAnnotationPresent)
      .flatMap(a => annotationTypeToWriter.get(a.annotationType))
  }

  private[this] val isRequiredAnnotationPresent: PartialFunction[Annotation, Annotation] = {
    case annotation: Annotation if annotation.annotationType.isAnnotationPresent(classOf[MessageBodyWriterAnnotation]) => annotation
  }
}
