package com.twitter.finatra.http.tests.integration.tweetexample.main.controllers

import com.twitter.concurrent.AsyncStream
import com.twitter.finagle.http.{Fields, Request, Response, Status}
import com.twitter.finatra.http.Controller
import com.twitter.finatra.http.response.{
  StreamingResponseUtils,
  StreamingResponse => DeprecatedStreamingResponse
}
import com.twitter.finatra.http.streaming.StreamingRequest
import com.twitter.finatra.http.tests.integration.tweetexample.main.domain.Tweet
import com.twitter.finatra.http.tests.integration.tweetexample.main.services.TweetsRepository
import com.twitter.io.{Buf, Reader}
import com.twitter.util.{Duration, Future, Try}
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import scala.collection.mutable

class TweetsController @Inject()(
  tweetsRepository: TweetsRepository,
  onWriteLog: mutable.ArrayBuffer[String])
    extends Controller {

  get("/tweets/hello") { _: Request =>
    "hello world"
  }

  post("/tweets/") { tweet: Tweet =>
    "tweet with id " + tweet.id + " is valid"
  }

  post("/tweets/streaming") { ids: Reader[Long] =>
    tweetsRepository.getByIds(ids)
  }

  post("/tweets/reader_buf_to_string") { bufs: Reader[Buf] =>
    bufs.map(buf => Buf.decodeString(buf, StandardCharsets.UTF_8))
  }

  post("/tweets/reader_int_to_string") { ints: Reader[Int] =>
    ints.map(buf => buf.toString)
  }

  post("/tweets/reader_buf") { bufs: Reader[Buf] =>
    bufs
  }

  post("/tweets/asyncStream_buf_to_string") { bufs: AsyncStream[Buf] =>
    bufs.map(buf => Buf.decodeString(buf, StandardCharsets.UTF_8))
  }

  post("/tweets/asyncStream_int_to_string") { ints: AsyncStream[Int] =>
    ints.map(buf => buf.toString)
  }

  post("/tweets/asyncStream_buf") { bufs: AsyncStream[Buf] =>
    bufs
  }

  get("/tweets/streaming_json") { _: Request =>
    tweetsRepository.getByIds(Reader.fromSeq(Seq(0, 1, 2, 3, 4, 5)))
  }

  get("/tweets/streaming_custom_tobuf_deprecated") { _: Request =>
    DeprecatedStreamingResponse(Buf.Utf8.apply) {
      AsyncStream("A", "B", "C")
    }
  }

  get("/tweets/streamingRep_with_asyncStream") { _: Request =>
    response.streaming(AsyncStream("A", "B", "C"))
  }

  post("/tweets/request_to_string") { request: Request =>
    request.contentString
  }

  post("/tweets/request_to_futurestring") { request: Request =>
    Future(request.contentString)
  }

  get("/tweets/streamingRep_with_reader") { _: Request =>
    val headerMap = Map[String, Seq[String]](
      "key1" -> Seq("value1", "value2", "value3"),
      "key2" -> Seq("v4", "v5", "v6")
    )
    response.streaming(Reader.fromSeq(Seq(1, 2, 3)), status = Status.Accepted, headers = headerMap)
  }

  get("/tweets/streaming_with_transformer_deprecated") { _: Request =>
    def lowercaseTransformer(as: AsyncStream[String]) = as.map(_.toLowerCase)

    val transformer = (lowercaseTransformer _)
      .andThen(StreamingResponseUtils.toBufTransformer(Buf.Utf8.apply))
      .andThen(StreamingResponseUtils.tupleTransformer(()))

    def onWrite(ignored: Unit, buf: Buf)(t: Try[Unit]): Unit = ()

    DeprecatedStreamingResponse(
      transformer,
      Status.Ok,
      Map.empty,
      onWrite,
      () => (),
      Duration.Zero
    ) {
      AsyncStream("A", "B", "C")
    }
  }

  get("/tweets/streamingRep_with_transformer_asyncStream") { _: Request =>
    response.streaming(AsyncStream("A", "B", "C").map(_.toLowerCase))
  }

  get("/tweets/streamingRep_with_transformer_reader") { _: Request =>
    response.streaming(Reader.fromSeq(Seq("A", "B", "C")).map(_.toLowerCase))
  }

  get("/tweets/streaming_with_onWrite_deprecated") { _: Request =>
    def serializeAndLowercase(as: AsyncStream[String]): AsyncStream[(String, Buf)] = {
      as.map(str => (str.toLowerCase, Buf.Utf8(str)))
    }

    def onWrite(lowerCased: String, buf: Buf)(t: Try[Unit]): Unit = {
      onWriteLog.append(lowerCased)
    }

    DeprecatedStreamingResponse(
      serializeAndLowercase,
      Status.Ok,
      Map.empty,
      onWrite,
      () => (),
      Duration.Zero
    ) {
      AsyncStream("A", "B", "C")
    }
  }

  get("/tweets/streaming_custom_tobuf_with_custom_headers_deprecated") { _: Request =>
    val headers = Map(
      Fields.ContentType -> "text/event-stream;charset=UTF-8",
      Fields.CacheControl -> "no-cache, no-store, max-age=0, must-revalidate",
      Fields.Pragma -> "no-cache"
    )

    DeprecatedStreamingResponse(Buf.Utf8.apply, Status.Created, headers) {
      AsyncStream("A", "B", "C")
    }
  }

  get("/tweets/streaming_with_onWrite") { _: Request =>
    def onWrite(lowerCased: String, buf: Buf): Buf = {
      onWriteLog.append(lowerCased)
      buf
    }
    val reader = Reader
      .fromSeq(Seq("A", "B", "C"))
      .map { str =>
        (str.toLowerCase, Buf.Utf8(str))
      }
      .map {
        case (loweredCase, buf) => onWrite(loweredCase, buf)
      }
    response.streaming(reader).toFutureResponse()
  }

  get("/tweets/streaming_custom_tobuf_with_custom_headers") { _: Request =>
    val headers = Map(
      Fields.ContentType -> Seq("text/event-stream;charset=UTF-8"),
      Fields.CacheControl -> Seq("no-cache", "no-store", "max-age=0", "must-revalidate"),
      Fields.Pragma -> Seq("no-cache")
    )

    response.streaming(AsyncStream("A", "B", "C"), Status.Created, headers).toFutureResponse()
  }

  get("/tweets/streaming_manual_writes") { _: Request =>
    val response = Response()
    response.setChunked(true)

    response.writer.write(Buf.Utf8("hello")) before {
      response.writer.write(Buf.Utf8("world")) ensure {
        response.close()
      }
    }

    Future(response)
  }

  post("/tweets/streaming_with_streamingRequest") {
    streamingRequest: StreamingRequest[Reader, Long] =>
      val reader = streamingRequest.stream
      tweetsRepository.getByIds(reader)
  }

  get("/tweets/not_streaming_with_streamingRequest/:id") {
    streamingRequest: StreamingRequest[Reader, Long] =>
      val id = streamingRequest.request.params("id").toLong
      tweetsRepository.getById(id)
  }

  post("/tweets/streaming_req_over_json") { streamingRequest: StreamingRequest[Reader, Tweet] =>
    val tweetReader = streamingRequest.stream
    val result: Reader[Long] = tweetReader.map { tweet =>
      tweet.id
    }
    response.streaming(result)
  }

  get("/tweets/streaming_rep_over_json") { streamingRequest: StreamingRequest[Reader, Long] =>
    val idReader = streamingRequest.stream
    val tweetReader: Reader[Tweet] = tweetsRepository.getByIds(idReader)
    response.streaming(tweetReader)
  }

  get("/tweets/") { _: Request =>
    "tweets root"
  }

  get("/tweets/:id") { request: Request =>
    val id = request.params("id").toLong
    tweetsRepository.getById(id)
  }

  get("/tweets/test/:id/") { request: Request =>
    request.params("id")
  }
}
