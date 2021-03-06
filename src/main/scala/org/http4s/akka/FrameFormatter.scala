package org.http4s.akka

import io.circe.{Decoder, Encoder, Json}
import io.circe.parser._
import org.http4s.{Charset, DefaultCharset, UrlForm}
import org.http4s.websocket.WebsocketBits._
import play.twirl.api._

/**
  * Typeclass to handle WebSocket frames format.
  */
trait FrameFormatter[A] { top =>
  def toFrame(value: A): WebSocketFrame
  val fromFrameDefinedFor: String
  def fromFrame(frame: WebSocketFrame): Option[A]
  
  /**
    * Transform a FrameFormatter[A] to a FrameFormatter[B]
    */
  def transform[B](fba: B => A, fab: A => B): FrameFormatter[B] = new FrameFormatter[B] {
    def toFrame(value: B): WebSocketFrame = top.toFrame(fba(value))
    lazy val fromFrameDefinedFor = top.fromFrameDefinedFor
    def fromFrame(frame: WebSocketFrame): Option[B] = top.fromFrame(frame).map(fab)
  }
}

/**
  * Defaults frame formatters.
  */
object FrameFormatter {
  implicit val stringFrameFormatter: FrameFormatter[String] = new FrameFormatter[String] {
    def toFrame(text: String): WebSocketFrame = Text(text)
    lazy val fromFrameDefinedFor: String = Text.getClass.getSimpleName
    def fromFrame(frame: WebSocketFrame): Option[String] = Option(frame) collect {
      case Text(text, _) => text
    }
  }
  implicit val byteArrayFrameFormatter: FrameFormatter[Array[Byte]] = new FrameFormatter[Array[Byte]] {
    def toFrame(bytes: Array[Byte]): WebSocketFrame = Binary(bytes)
    lazy val fromFrameDefinedFor: String = Binary.getClass.getSimpleName
    def fromFrame(frame: WebSocketFrame): Option[Array[Byte]] = Option(frame) collect {
      case Binary(bytes, _) => bytes
    }
  }
  implicit def urlFormFrameFormatter(implicit charset: Charset = DefaultCharset): FrameFormatter[UrlForm] = {
    stringFrameFormatter.transform[UrlForm](
      UrlForm.encodeString(charset)(_),
      UrlForm.decodeString(charset)(_).toTry.get
    )
  }
  
  //=== CIRCE ===
  /** Json WebSocket frames. */
  implicit val jsonFrameFormatter: FrameFormatter[Json] = {
    stringFrameFormatter.transform(_.noSpaces, s => parse(s) match {
      case Right(json) => json
      case Left(parsingFailure) =>
        throw parsingFailure.copy(message = s"${parsingFailure.message} for input\n$s")
    })
  }
  /** Json WebSocket frames, parsed into/formatted from objects of type A. */
  implicit def circeableFrameFormat[A: Encoder : Decoder]: FrameFormatter[A] = jsonFrameFormatter.transform[A](
    Encoder[A].apply(_),
    Decoder[A].decodeJson(_).toTry.get
  )
  
  //=== TWIRL ===
  implicit val htmlFrameFormatter: FrameFormatter[Html] = stringFrameFormatter.transform(_.body, Html.apply)
  implicit val xmlFrameFormatter: FrameFormatter[Xml] = stringFrameFormatter.transform(_.body, Xml.apply)
  implicit val txtFrameFormatter: FrameFormatter[Txt] = stringFrameFormatter.transform(_.body, Txt.apply)
  implicit val javaScriptFrameFormatter: FrameFormatter[JavaScript] = stringFrameFormatter.transform(_.body, JavaScript.apply)
}
