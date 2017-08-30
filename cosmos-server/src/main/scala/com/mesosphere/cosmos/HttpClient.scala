package com.mesosphere.cosmos

import com.mesosphere.cosmos.error.UnsupportedContentEncoding
import com.mesosphere.cosmos.error.UnsupportedRedirect
import com.mesosphere.cosmos.http.MediaType
import com.mesosphere.cosmos.http.MediaTypeParser
import com.netaporter.uri.Uri
import com.twitter.finagle.http.Fields
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.Future
import com.twitter.util.Return
import com.twitter.util.Throw
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URISyntaxException
import java.util.zip.GZIPInputStream
import org.slf4j.Logger

trait HttpClient {

  import HttpClient._

  lazy val logger: Logger = org.slf4j.LoggerFactory.getLogger(getClass)

  def fetch[A](
    uri: Uri,
    statsReceiver: StatsReceiver,
    headers: (String, String)*
  )(
    processResponse: ResponseData => A
  ): Future[Either[HttpClient.Error, A]]

}

object HttpClient extends HttpClient {

  def fetch[A](
    uri: Uri,
    statsReceiver: StatsReceiver,
    headers: (String, String)*
  )(
    processResponse: ResponseData => A
  ): Future[Either[Error, A]] = {
    implicit val sr: StatsReceiver = statsReceiver

    Future(uri.toURI.toURL.openConnection())
      .handle {
        case t @ (_: IllegalArgumentException | _: MalformedURLException | _: URISyntaxException) =>
          throw UriSyntax(t)
      }
      .flatMap { case conn: HttpURLConnection =>
        headers.foreach { case (name, value) => conn.setRequestProperty(name, value) }

        val responseData = extractResponseData(uri, conn)

        Future(Right(processResponse(responseData)))
          .ensure(responseData.contentStream.close())
          .ensure(conn.disconnect())
      }
      .handle { case e: IOException => throw UriConnection(e) }
      .handle { case e: Error => Left(e) }
  }

  private def extractResponseData(
    uri: Uri,
    conn: HttpURLConnection
  )(implicit
    sr: StatsReceiver
  ): ResponseData = {
    val (contentType, contentEncoding) = parseContentHeaders(uri, conn)

    val contentLength = conn.getContentLengthLong match {
      case len if len < 0 => None
      case len => Some(len)
    }

    val contentStream = prepareContentStream(conn, contentEncoding)
    ResponseData(contentType, contentLength, contentStream)
  }

  private def parseContentHeaders(
    uri: Uri,
    conn: HttpURLConnection
  )(implicit
    sr: StatsReceiver
  ): (MediaType, Option[String]) = {
    conn.getResponseCode match {
      case HttpURLConnection.HTTP_OK =>
        sr.scope("status").counter("200").incr()
        // TODO proxy Handle error cases
        val contentEncoding = Option(conn.getHeaderField(Fields.ContentEncoding))
        MediaTypeParser.parse(conn.getHeaderField(Fields.ContentType)) match {
          case Return(contentType) => (contentType, contentEncoding)
          case Throw(error) => {
            logger.error(s"Error while parsing the Content-Type " +
              s"${conn.getHeaderField(Fields.ContentType)} from URI $uri",
              error
            )
            throw error
          }
        }
      case status if RedirectStatuses(status) =>
        sr.scope("status").counter(status.toString).incr()
        // Different forms of redirect, HttpURLConnection won't follow a redirect across schemes
        val loc = Option(conn.getHeaderField("Location")).map(Uri.parse).flatMap(_.scheme)
        throw UnsupportedRedirect(List(uri.scheme.get), loc).exception
      case status =>
        sr.scope("status").counter(status.toString).incr()
        throw UnexpectedStatus(status)
    }
  }

  private def prepareContentStream(
    conn: HttpURLConnection,
    contentEncoding: Option[String]
  )(implicit
    sr: StatsReceiver
  ): InputStream = {
    contentEncoding match {
      case Some("gzip") =>
        sr.scope("contentEncoding").counter("gzip").incr()
        new GZIPInputStream(conn.getInputStream)
      case ce @ Some(_) =>
        throw UnsupportedContentEncoding(List("gzip"), ce).exception
      case _ =>
        sr.scope("contentEncoding").counter("plain").incr()
        conn.getInputStream
    }
  }

  val TemporaryRedirect: Int = 307
  val PermanentRedirect: Int = 308

  val RedirectStatuses: Set[Int] = {
    Set(
      HttpURLConnection.HTTP_MOVED_PERM,
      HttpURLConnection.HTTP_MOVED_TEMP,
      HttpURLConnection.HTTP_SEE_OTHER,
      TemporaryRedirect,
      PermanentRedirect
    )
  }

  final case class ResponseData(
    contentType: MediaType,
    contentLength: Option[Long],
    contentStream: InputStream
  )

  sealed trait Error extends Exception
  final case class UriSyntax(cause: Throwable) extends Error
  final case class UriConnection(cause: IOException) extends Error
  final case class UnexpectedStatus(clientStatus: Int) extends Error

}
