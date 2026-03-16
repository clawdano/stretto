package stretto.node

import stretto.core.{Point, Tip}

/** Lightweight events published when the chain state changes. */
enum ChainEvent:
  /** A new block was added to the chain. */
  case BlockAdded(point: Point.BlockPoint, tip: Tip)

  /** The chain rolled back to a previous point. */
  case RolledBack(point: Point, tip: Tip)
