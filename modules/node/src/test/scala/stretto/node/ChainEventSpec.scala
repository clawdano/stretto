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

  /** Wait until the topic has at least `n` subscribers. */
  private def waitForSubscribers(topic: Topic[IO, ChainEvent], n: Int): IO[Unit] =
    topic.subscribers
      .dropWhile(_ < n)
      .take(1)
      .compile
      .drain

  test("Topic publishes BlockAdded events to subscribers") {
    for
      topic <- Topic[IO, ChainEvent]
      fiber <- topic.subscribe(16).take(1).compile.lastOrError.start
      _     <- waitForSubscribers(topic, 1)
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
      topic <- Topic[IO, ChainEvent]
      fiber <- topic.subscribe(16).take(1).compile.lastOrError.start
      _     <- waitForSubscribers(topic, 1)
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
      topic  <- Topic[IO, ChainEvent]
      fiber1 <- topic.subscribe(16).take(1).compile.lastOrError.start
      fiber2 <- topic.subscribe(16).take(1).compile.lastOrError.start
      _      <- waitForSubscribers(topic, 2)
      _      <- topic.publish1(ChainEvent.BlockAdded(samplePoint, sampleTip))
      event1 <- fiber1.joinWithNever
      event2 <- fiber2.joinWithNever
    yield
      assertEquals(event1, event2)
      assertEquals(event1, ChainEvent.BlockAdded(samplePoint, sampleTip))
  }
