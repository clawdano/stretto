package stretto.core

import org.bouncycastle.crypto.digests.Blake2bDigest
import scodec.bits.ByteVector

/** Cryptographic hash functions used throughout Cardano. */
object Crypto:

  /** Compute Blake2b-256 hash. Used for block header hashes, tx hashes, etc. */
  def blake2b256(data: ByteVector): ByteVector =
    val digest = new Blake2bDigest(256)
    val input  = data.toArray
    digest.update(input, 0, input.length)
    val output = new Array[Byte](32)
    digest.doFinal(output, 0)
    ByteVector.view(output)
