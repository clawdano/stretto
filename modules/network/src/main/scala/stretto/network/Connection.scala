package stretto.network

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, Port}
import fs2.io.net.{Network, Socket}
import scodec.bits.ByteVector

/** A multiplexed connection to a Cardano node. */
final class MuxConnection private[network] (
    val socket: Socket[IO],
    val mux: MuxDemuxer,
    val acceptedVersion: Int
):

  /** Send a payload on a mini-protocol (initiator direction). */
  def send(miniProtocolId: Int, payload: ByteVector): IO[Unit] =
    mux.send(miniProtocolId, payload)

  /** Receive demultiplexed frames as a stream of (miniProtocolId, payload). */
  def receive: fs2.Stream[IO, (Int, ByteVector)] =
    mux.receive

object MuxConnection:

  /** Connect to a Cardano node, perform the handshake, and return a MuxConnection. */
  def connect(
      host: String,
      port: Int,
      networkMagic: Long
  ): Resource[IO, MuxConnection] =
    for
      socket  <- openSocket(host, port)
      mux     <- Resource.eval(MuxDemuxer(socket))
      version <- Resource.eval(performHandshake(mux, networkMagic))
    yield new MuxConnection(socket, mux, version)

  private def openSocket(host: String, port: Int): Resource[IO, Socket[IO]] =
    val hostParsed = Host.fromString(host)
    val portParsed = Port.fromInt(port)
    (hostParsed, portParsed) match
      case (Some(h), Some(p)) =>
        Network[IO].client(
          com.comcast.ip4s.SocketAddress(h, p)
        )
      case _ =>
        Resource.eval(
          IO.raiseError(
            new IllegalArgumentException(
              s"Invalid host:port — $host:$port"
            )
          )
        )

  private def performHandshake(
      mux: MuxDemuxer,
      networkMagic: Long
  ): IO[Int] =
    val propose = HandshakeMessage.handshakeClient(networkMagic)
    val encoded = HandshakeMessage.encode(propose)
    for
      _ <- mux.send(MiniProtocolId.Handshake.id, encoded)
      response <- mux.receive
        .filter(_._1 == MiniProtocolId.Handshake.id)
        .head
        .compile
        .lastOrError
      (_, payload) = response
      result <- HandshakeMessage.decode(payload) match
        case Right(HandshakeMessage.MsgAcceptVersion(version, _)) =>
          IO.pure(version)
        case Right(HandshakeMessage.MsgRefuse(reason)) =>
          IO.raiseError(
            new RuntimeException(
              s"Handshake refused: ${reason.toHex}"
            )
          )
        case Right(_) =>
          IO.raiseError(
            new RuntimeException("Unexpected handshake response")
          )
        case Left(err) =>
          IO.raiseError(new RuntimeException(s"Handshake decode error: $err"))
    yield result
