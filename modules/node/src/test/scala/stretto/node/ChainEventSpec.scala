package stretto.node

import cats.effect.IO
import munit.CatsEffectSuite
import fs2.concurrent.Topic
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*

class ChainEventSpec extends CatsEffectSuite:

  private val sampleHash                    = Hash32.unsafeFrom(ByteVector.fill(32)(0xbb))
  private val sampleBhh                     = BlockHeaderHash(sampleHash)
  private val samplePoint: Point.BlockPoint = Point.BlockPoint(SlotNo(42L), sampleBhh)
  private val sampleTip                     = Tip(samplePoint, BlockNo(100L))

  test("Topic publishes BlockAdded events to subscribers") {
    for
      topic  <- Topic[IO, ChainEvent]
      signal <- IO.deferred[Unit]
      fiber <- (
        signal.complete(()) *>
          topic.subscribe(16).take(1).compile.lastOrError
      ).start
      _     <- signal.get // Wait until subscribe is about to start
      _     <- IO.sleep(scala.concurrent.duration.FiniteDuration(100, "ms"))
      _     <- topic.publish1(ChainEvent.BlockAdded(samplePoint, sampleTip))
      event <- fiber.joinWithNever
    yield event match
      case ChainEvent.BlockAdded(point, tip) =>
        assertEquals(point, samplePoint)
        assertEquals(tip, sampleTip)
      case _ => fail("Expected BlockAdded event")
  }

  test("Topic publishes RolledBack events to subscribers") {
    for
      topic  <- Topic[IO, ChainEvent]
      signal <- IO.deferred[Unit]
      fiber <- (
        signal.complete(()) *>
          topic.subscribe(16).take(1).compile.lastOrError
      ).start
      _     <- signal.get
      _     <- IO.sleep(scala.concurrent.duration.FiniteDuration(100, "ms"))
      _     <- topic.publish1(ChainEvent.RolledBack(Point.Origin, Tip.origin))
      event <- fiber.joinWithNever
    yield event match
      case ChainEvent.RolledBack(point, tip) =>
        assertEquals(point, Point.Origin)
        assertEquals(tip, Tip.origin)
      case _ => fail("Expected RolledBack event")
  }

  test("Multiple subscribers receive the same event") {
    for
      topic   <- Topic[IO, ChainEvent]
      signal1 <- IO.deferred[Unit]
      signal2 <- IO.deferred[Unit]
      fiber1 <- (
        signal1.complete(()) *>
          topic.subscribe(16).take(1).compile.lastOrError
      ).start
      fiber2 <- (
        signal2.complete(()) *>
          topic.subscribe(16).take(1).compile.lastOrError
      ).start
      _      <- signal1.get *> signal2.get
      _      <- IO.sleep(scala.concurrent.duration.FiniteDuration(100, "ms"))
      _      <- topic.publish1(ChainEvent.BlockAdded(samplePoint, sampleTip))
      event1 <- fiber1.joinWithNever
      event2 <- fiber2.joinWithNever
    yield
      assertEquals(event1, event2)
      assertEquals(event1, ChainEvent.BlockAdded(samplePoint, sampleTip))
  }
