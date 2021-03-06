package com.zaneli.escalade.gyazo

import com.zaneli.escalade.gyazo.model.{ ImagesList, Image }
import eu.medsea.mimeutil.MimeUtil
import java.io.{ File, FileInputStream }
import java.nio.file.Files
import org.json4s.{ DefaultFormats, JString }
import org.json4s.native.JsonMethods.parse
import scalaj.http.{ Http, HttpRequest, MultiPart }

class GyazoClient(accessToken: String, base: BaseClient = HttpClient) {

  private[this] var cachedImages: Option[(ETag, List[Image])] = None

  private[this] val tokenHeader = ("Authorization", "Bearer " + accessToken)
  private[this] def etagHeader(etag: String) = ("If-None-Match", etag)

  MimeUtil.registerMimeDetector("eu.medsea.mimeutil.detector.MagicMimeMimeDetector")

  def list(page: Int = 1, perPage: Int = 20): ImagesList = {
    val params = Map("page" -> page, "per_page" -> perPage)
    val (code, headers, body) = cachedImages match {
      case Some((etag, _)) => base.exec("images", params, "get", tokenHeader, etagHeader(etag))
      case _ => base.exec("images", params, "get", tokenHeader)
    }
    if (code == 304) {
      val res = ImagesList(headers)
      res.copy(images = cachedImages.map(_._2).getOrElse(Nil))
    } else {
      val res = ImagesList(headers, parse(body))
      cachedImages = getHeaderValue(headers, "ETag", identity).map(etag => (etag, res.images))
      res
    }
  }

  def upload(image: File): Image = {
    val (_, _, body) = base.multipart("upload", "imagedata", image, tokenHeader)
    Image(parse(body))
  }

  def delete(imageId: String): (String, String) = {
    implicit val formats = DefaultFormats
    val (_, _, body) = base.exec(s"images/${imageId}", Map(), "delete", tokenHeader)
    val value = parse(body)
    val JString(deletedId) = value \ "image_id"
    val JString(deletedImageType) = value \ "type"
    (deletedId, deletedImageType)
  }
}

trait BaseClient {
  def exec(
    path: String, params: Map[String, Any], method: String, hs: (String, String)*): (StatusCode, Map[String, String], String)
  def multipart(
    path: String, name: String, file: File, hs: (String, String)*): (StatusCode, Map[String, String], String)
}

private[gyazo] object HttpClient extends BaseClient {
  private[this] val apiHost = "api.gyazo.com"
  private[this] val uploadHost = "upload.gyazo.com"

  override def exec(
    path: String, params: Map[String, Any], method: String, hs: (String, String)*): (StatusCode, Map[String, String], String) = {
    doExec(Http(mkUrl(apiHost, path)).params(params.map { case (k, v) => (k, v.toString) }), method, hs: _*)
  }

  override def multipart(
    path: String, name: String, file: File, hs: (String, String)*): (StatusCode, Map[String, String], String) = {
    val req = Http(mkUrl(uploadHost, path)).postMulti(MultiPart(name, file.getName, getMimeType(file), Files.readAllBytes(file.toPath)))
    doExec(req, "post", hs: _*)
  }

  private[this] def doExec(req: HttpRequest, method: String, hs: (String, String)*): (StatusCode, Map[String, String], String) = {
    val res = ((req.method(method).timeout(connTimeoutMs = 5000, readTimeoutMs = 5000), hs) match {
      case (req, Nil) => req
      case (req, _) => req.headers(hs.head, hs.tail: _*)
    }).asString
    if (res.isError) {
      throw new IllegalStateException(s"${res.code}: ${res.body}")
    }
    (res.code, res.headers.toMap, res.body)
  }

  private[this] def mkUrl(host: String, path: String): String = s"https://${host}/api/${path}"

  private[this] def getMimeType(file: File): String = {
    import scala.collection.JavaConversions._
    MimeUtil.getMimeTypes(file).head.toString
  }
}
