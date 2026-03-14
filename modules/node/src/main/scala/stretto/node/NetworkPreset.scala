package stretto.node

import stretto.network.NetworkMagic

/** Well-known Cardano network configurations with default relay peers. */
final case class NetworkPreset(
    name: String,
    networkMagic: Long,
    defaultPeers: List[(String, Int)]
)

object NetworkPreset:

  val Mainnet: NetworkPreset = NetworkPreset(
    name = "mainnet",
    networkMagic = NetworkMagic.Mainnet,
    defaultPeers = List(
      "backbone.cardano.iog.io"                -> 3001,
      "backbone.mainnet.cardanofoundation.org" -> 3001,
      "relays-new.cardano-mainnet.iohk.io"     -> 3001
    )
  )

  val Preprod: NetworkPreset = NetworkPreset(
    name = "preprod",
    networkMagic = NetworkMagic.Preprod,
    defaultPeers = List(
      "preprod-node.play.dev.cardano.org" -> 3001,
      "preprod.world.dev.cardano.org"     -> 30000
    )
  )

  val Preview: NetworkPreset = NetworkPreset(
    name = "preview",
    networkMagic = NetworkMagic.Preview,
    defaultPeers = List(
      "preview-node.play.dev.cardano.org" -> 3001,
      "preview.world.dev.cardano.org"     -> 30002
    )
  )

  val All: List[NetworkPreset] = List(Mainnet, Preprod, Preview)

  def fromName(name: String): Option[NetworkPreset] =
    All.find(_.name.equalsIgnoreCase(name))
