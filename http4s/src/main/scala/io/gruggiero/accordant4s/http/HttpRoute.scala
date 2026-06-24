package io.gruggiero.accordant4s.http

import cats.effect.IO
import org.http4s.{EntityEncoder, Method, Request, Uri}

/**
 * One operation's HTTP shape: a URI builder and request encoding. The URI
 * builder turns the request into a path/query; `encode` produces a full
 * `Request[IO]` (method + uri + entity). Built FROM an operation so its `Req`
 * is statically known.
 */
final case class HttpRoute[Req](
    uri: Req => Uri,
    encode: Req => Request[IO]
)

object HttpRoute:

  /**
   * Convenience builder for a JSON-bodied POST route. Derives the `encode`
   * function from the http4s `EntityEncoder[IO, Req]` (typically from
   * `jsonEncoderOf`), so the caller only supplies the URI builder.
   */
  def jsonPost[Req](uri: Req => Uri)(using
      entityEnc: EntityEncoder[IO, Req]
  ): HttpRoute[Req] =
    HttpRoute(
      uri = uri,
      encode = (req: Req) => Request[IO](method = Method.POST, uri = uri(req)).withEntity(req)
    )
