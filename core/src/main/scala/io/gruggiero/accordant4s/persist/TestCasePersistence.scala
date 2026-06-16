package io.gruggiero.accordant4s.persist

import io.circe.generic.semiauto.{deriveCodec, deriveDecoder, deriveEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}
import io.gruggiero.accordant4s.domain.CallLabel
import io.gruggiero.accordant4s.spec.{OperationCall, TestCase}

/**
 * circe JSON persistence for [[TestCase]] inside a versioned [[TestCaseFileRecord]]
 * envelope — the "reproducing path" artifact for failing cases.
 *
 * The existential request inside each [[OperationCall]] is serialised by a
 * USER-supplied `Codec[OperationCall[S]]` (plus a `Codec[S]` for the state); the
 * library supplies the `CallLabel` and `TestCase` codecs on top. Loading never
 * throws: an unknown schema version yields `VersionMismatch`, and malformed or
 * structurally invalid JSON yields `DecodeFailed`.
 */
object TestCasePersistence:

  /** The only schema version this build writes and accepts. */
  val schemaVersion: Int = 1

  /** Library default for the envelope's informational `specName`. */
  private val specName: String = "accordant4s"

  /** `CallLabel` ⇄ JSON string; decoding refines through the smart constructor. */
  given callLabelCodec: Codec[CallLabel] =
    Codec.from(
      Decoder.decodeString.emap(CallLabel.either),
      Encoder.encodeString.contramap[CallLabel](label => label: String)
    )

  /** `TestCase[S]` codec, derived from the user's state and operation-call codecs. */
  given testCaseCodec[S](using Codec[S], Codec[OperationCall[S]]): Codec[TestCase[S]] =
    deriveCodec

  def toJson[S](tc: TestCase[S])(using Encoder[TestCase[S]]): Json =
    given Encoder[TestCaseFileRecord[S]] = deriveEncoder
    TestCaseFileRecord(schemaVersion, specName, tc).asJson

  def fromJson[S](json: Json)(using Decoder[TestCase[S]]): Either[PersistenceError, TestCase[S]] =
    given Decoder[TestCaseFileRecord[S]] = deriveDecoder
    json.hcursor.get[Int]("schemaVersion") match
      case Left(error) => Left(PersistenceError.DecodeFailed(error))
      case Right(found) if found != schemaVersion =>
        Left(PersistenceError.VersionMismatch(found, schemaVersion))
      case Right(_) =>
        json.as[TestCaseFileRecord[S]] match
          case Left(error)   => Left(PersistenceError.DecodeFailed(error))
          case Right(record) => Right(record.testCase)
