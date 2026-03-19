package cardano.kes

import munit.FunSuite
import scodec.bits.ByteVector
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * KES conformance tests using real Cardano block header data.
 *
 * Test vectors extracted from real mainnet/testnet blocks (Pallas-sourced fixtures).
 * These tests verify that SumKES.verify works correctly with the 448-byte
 * standard wire format (Sum6KES) used in actual Cardano block headers.
 *
 * Each test vector contains:
 *   - hotVkey: The KES root verification key (32 bytes, from OCert)
 *   - relativePeriod: The relative KES period (empirically verified)
 *   - rawHeaderBody: The signed message (raw CBOR of the header body)
 *   - kesSignature: The 448-byte standard Sum6KES signature
 *
 * Sources:
 *   - shelley1.block: Mainnet Shelley-era block (slot 7948610, block 4662237)
 *   - babbage1.block: Testnet Babbage-era block (slot 1029948, block 44697)
 *   - conway1.block: Preprod Conway-era block (slot 22075282, block 1093546)
 *   - conway2.block: Preprod Conway-era block (slot 23971491, block 1183499)
 */
class SumKESConformanceSpec extends FunSuite:

  private def verifyEd25519(publicKey: ByteVector, message: ByteVector, signature: ByteVector): Boolean =
    if publicKey.size != 32 || signature.size != 64 then return false
    scala.util
      .Try {
        val pubKeyParams = new Ed25519PublicKeyParameters(publicKey.toArray, 0)
        val verifier     = new Ed25519Signer()
        verifier.init(false, pubKeyParams)
        verifier.update(message.toArray, 0, message.size.toInt)
        verifier.verifySignature(signature.toArray)
      }
      .getOrElse(false)

  // ===========================================================================
  // Test vectors from real Cardano blocks
  //
  // Each vector includes a `relativePeriod` that has been empirically verified
  // against the KES signature. The period is computed as:
  //   currentKesPeriod = slotNo / slotsPerKesEvolution
  //   relativePeriod = currentKesPeriod - startKesPeriod
  // where slotsPerKesEvolution depends on the network's genesis configuration.
  // ===========================================================================

  /**
   * Shelley-era mainnet block: slot=7948610, block=4662237.
   *  slotsPerKesEvolution=129600 (mainnet), relativePeriod=6.
   */
  object Shelley1:
    val relativePeriod = 6
    val slotNo         = 7948610L
    val startKesPeriod = 55L
    val issuerVkey     = ByteVector.fromValidHex("8b53207629f9a30e4b2015044f337c01735abe67243c19470c9dae8c7b732798")
    val hotVkey        = ByteVector.fromValidHex("674617ebe299bcba144026e4342e9f54c861165c1dde1373fd1206e654f985b8")
    val ocertCounter   = 0L

    val coldSignature = ByteVector.fromValidHex(
      "5befdeffa73bc8b4a1cd22aa2c896f189a698175e5bfc4a562e3a15b5f6580953e6fcc72a37386816031e36fdf19718351417f01af02c7314fbe9f2792b29e0c"
    )

    val kesSignature = ByteVector.fromValidHex(
      "c597caba74923b7901d5b8162f27338413b12941e411e9371b06a01375b8690aab067a42dde22db909bf77db373ca8645b751711256ba5f360e2935f64d14104cfdfb6c7865c6f4219e67af060cdcf4dc3d874ede00c394e9ecac7ba1f663b367e0f482e1bfaff08808d6567590cd6bf43c849ebacfb5fb185f4592ff3bf0a479d5a1f3f19f819b59f662cad2b6ff2187ec94b4c5fac6b8375b02d6b52d229ae24b389ff2d72b584f47f77cbc62a43f1880e486fda30ac1600f475dc4857e66090fe7399f4e3bff4929ea1c1929371846a34391473c79f9409f05f65fe8d2acb6f5eceb84474555d163db96d809aa77b9c2f80156d0356e75204ab5032e833bbacecf407038c8a28da4900c1c63a5bb32672dd345c37e1c866b15da5d2c41ab76c214bc8e3efd9e34cf092f1166edc2de2b03ccaca01b2c0261bfaf3f166f3937c21128c3ebb96ceefab6c80897da9f096a7cc113c4b9c0cd8b97fe3d29f6a2c9960005d2f1ce2e8bfeca9b1f8ebe80637f59133692e11ad9f9c557c10102472aef7f472d72920bbff7a7f4e344988c2d5f98482fbbae7a081d7f10b55b33a7c4dea90483223bea2093cb068b2db39973dd06700ea4eb65fa3210d7d53430c9c"
    )

    val rawHeaderBody = ByteVector.fromValidHex(
      "8f1a004723dd1a007949425820c175f470d30216341423a98a6087175642250acec7d9f53a311cf2e0a1c9c7b258208b53207629f9a30e4b2015044f337c01735abe67243c19470c9dae8c7b732798582090561cf5fb4eada778f0564060b9b5138fbfa50c0e74fc496956c8c3507301a6825840d266d923d59fc8a1b7e964dab2b6db804b494c202586eae8e2db929ca2361d9f01154c4a78b95a2e6bf19ebe98e775f894ad53971bd1ceeee125ee8473747d60585011614e11e284d28aa303da9ca3a37bfde35f931d308ae3da36e381ac42910d36dc26d91bfa726d7b4a7ae1fb263e037e8f9e80e3411a8754863b8b5601047b9e04d0f72f00206ea616c6cffc75fc48018258405620f9239d562aed34442b72c8bc840bb9a5ef897b470132430a02cd0ce69052a6ebb17896177180c1d88afed3d7614878549c5573c0f281d5dad2f29bda5a6d5850f349597045cc5f65a9724770f971e6964e09fd85db8e36ef789f390afd4629a3f5e96b4e5ee8280ec26236a6323cbc16867a1868645566e0607d7a474fd7d06b44c3afbcd85a41098a80ba6faeb7400b190596582000ef8e1bebe7d404a910c7c467fb5aafbc7dee7fcaac94cb9693e08ea9dd7d2a5820674617ebe299bcba144026e4342e9f54c861165c1dde1373fd1206e654f985b800183758405befdeffa73bc8b4a1cd22aa2c896f189a698175e5bfc4a562e3a15b5f6580953e6fcc72a37386816031e36fdf19718351417f01af02c7314fbe9f2792b29e0c0200"
    )

  /**
   * Babbage-era testnet block: slot=1029948, block=44697.
   *  This block originates from a testnet with slotsPerKesEvolution=86400, not mainnet's 129600.
   *  relativePeriod=11 (empirically verified by exhaustive period scan).
   */
  object Babbage1:
    val relativePeriod = 11
    val slotNo         = 1029948L
    val startKesPeriod = 0L
    val issuerVkey     = ByteVector.fromValidHex("63fc404a8f5635aa133818457a406f62d946dd35362ef78429da4953b5125ab1")
    val hotVkey        = ByteVector.fromValidHex("840ba03379518ab70200edd70a984cd5fcf4ef8f0994c95cc394d3e7243de3f1")
    val ocertCounter   = 0L

    val coldSignature = ByteVector.fromValidHex(
      "a352185eb0e2783206e23d0aa25b8d799a228266baf65de832cd2b64d666fbdd5649461d3cd30f721bc04e94bb9f97c31335ee39893247eaf9912cfa68878504"
    )

    val kesSignature = ByteVector.fromValidHex(
      "954157d0a93992a0cda655574074541b92a5d12cba0a94dc8dc7c97de407dc6bbb463810a6fec0ae47c7d4d3cddcb51485616e924448e9850369efdf9f248d0ff052309c26385a7bff045e39d4afdb90cc114b06215ee4c2ba026b9109aecf87e0547eeab8973a3f5903403fea20416357881a2226733dafd3700154992ad454f75383438acab170e4fca6691d40ed986df32b006d9408cb70a1265c52673cb7c361b646b8a1e47b92b015dd82743ce75680caa0b3337dbec280b91d4fcf31e97630d09977f264eb4c4794647734532a36538896e640982f52649f808a26e71782232617c944e8ab2bf62e59772123b68d136b3a06c0d3263fbfc51f4225a18d788b4f45b62741472e72d107be72e63265cf83f389b4ced1b5246e5f4b1c2aeb35d7f60b1e417940b917f0ad9cc7962f28dcd243cac00b7a4c2d233a1e3dc0749d5430d777d41425c9cac7f7232f620f0ed8e5f400b7c2a1508e5e61adcd2d5bf76d9db9576cd1d87ba1912567b493c7e20756e970a731a05ee20ef2db22f8b7959323025072ecdb163adb96878bea9aee92b67556ae38ee764e37ce65d74c5c4255ec86defaf48cd31d9ab7b868606f050c816119d7c00dbbf66c248cbfc4a2"
    )

    val rawHeaderBody = ByteVector.fromValidHex(
      "8a19ae991a000fb73c582068e0b30103cba96819b7f7abbe80f4aae6fb35270c3c77c4a8f82d9ab21a920e582063fc404a8f5635aa133818457a406f62d946dd35362ef78429da4953b5125ab15820a9a971c7cc826c53a9a1690ae9c6a46123d85ac90641c859da408cbc7ce6b3eb825840e5b74253ccd2a78e4e5653499151fb1c01e0c009d3b6e80feb4d15c6ef10abe9683b652ca968a1ae124be7b59eb1f4ebfd882671da49101635b1995e1e42a2ef58500ec054de37747089cd4fd9864b2fe17cdb2567adf9ee89a44c29e81fad88d663bd76d6abd65848ba0df146df1f95a6eb16e7d794332323e359442a9f2830d5b5b052e00bcd147c7e5bed987a70580f021903385820219f8b90967156d6ee02e3507ccb413b5a8d87343706cbd9e8fb1e1285e4b3c0845820840ba03379518ab70200edd70a984cd5fcf4ef8f0994c95cc394d3e7243de3f100005840a352185eb0e2783206e23d0aa25b8d799a228266baf65de832cd2b64d666fbdd5649461d3cd30f721bc04e94bb9f97c31335ee39893247eaf9912cfa68878504820700"
    )

  /**
   * Conway-era preprod block: slot=22075282, block=1093546.
   *  slotsPerKesEvolution=129600 (preprod), relativePeriod=5.
   */
  object Conway1:
    val relativePeriod = 5
    val slotNo         = 22075282L
    val startKesPeriod = 165L
    val issuerVkey     = ByteVector.fromValidHex("e856c84a3d90c8526891bd58d957afadc522de37b14ae04c395db8a7a1b08c4a")
    val hotVkey        = ByteVector.fromValidHex("0ca1ec2c1c2af308bd9e7a86eb12d603a26157752f3f71c337781c456e6ed0c9")
    val ocertCounter   = 0L

    val coldSignature = ByteVector.fromValidHex(
      "8e554b644a2b25cb5892d07a26c273893829f1650ec33bf6809d953451c519c32cfd48d044cd897a17cdef154d5f5c9b618d9b54f8c49e170082c08c23652409"
    )

    val kesSignature = ByteVector.fromValidHex(
      "5a96b747789ef6678b2f4a2a7caca92e270f736e9b621686f95dd1332005102faee21ed50cf6fa6c67e38b33df686c79c91d55f30769f7c964d98aa84cbefe0a808ee6f45faaf9badcc3f746e6a51df1aa979195871fd5ffd91037ea216803be7e7fccbf4c13038c459c7a14906ab57f3306fe155af7877c88866eede7935f642f6a72f1368c33ed5cc7607c995754af787a5af486958edb531c0ae65ce9fdce423ad88925e13ef78700950093ae707bb1100299a66a5bb15137f7ba62132ba1c9b74495aac50e1106bacb5db2bed4592f66b610c2547f485d061c6c149322b0c92bdde644eb672267fdab5533157ff398b9e16dd6a06edfd67151e18a3ac93fc28a51f9a73f8b867f5f432b1d9b5ae454ef63dea7e1a78631cf3fee1ba82db61726701ac5db1c4fee4bb6316768c82c0cdc4ebd58ccc686be882f9608592b3c718e4b5d356982a6b83433fe76d37394eff9f3a8e4773e3bab9a8b93b4ea90fa33bfbcf0dc5a21bfe64be2eefaa82c0494ab729e50596110f60ae9ad64b3eb9ddb54001b03cc264b65634c071d3b24a44322f39a9eae239fd886db8d429969433cb2d0a82d7877f174b0e154262f1af44ce5bc053b62daadd2926f957440ff39"
    )

    val rawHeaderBody = ByteVector.fromValidHex(
      "8a1a0010afaa1a0150d7925820a22f65265e7a71cfc3b637d6aefe8f8241d562f5b1b787ff36697ae4c3886f185820e856c84a3d90c8526891bd58d957afadc522de37b14ae04c395db8a7a1b08c4a582015587d5633be324f8de97168399ab59d7113f0a74bc7412b81f7cc1007491671825840af9ff8cb146880eba1b12beb72d86be46fbc98f6b88110cd009bd6746d255a14bb0637e3a29b7204bff28236c1b9f73e501fed1eb5634bd741be120332d25e5e5850a9f1de24d01ba43b025a3351b25de50cc77f931ed8cdd0be632ad1a437ec9cf327b24eb976f91dbf68526f15bacdf8f0c1ea4a2072df9412796b34836a816760f4909b98c0e76b160d9aec6b2da060071903705820b5858c659096fcc19f2f3baef5fdd6198641a623bd43e792157b5ea3a2ecc85c8458200ca1ec2c1c2af308bd9e7a86eb12d603a26157752f3f71c337781c456e6ed0c90018a558408e554b644a2b25cb5892d07a26c273893829f1650ec33bf6809d953451c519c32cfd48d044cd897a17cdef154d5f5c9b618d9b54f8c49e170082c08c23652409820900"
    )

  /**
   * Conway-era preprod block: slot=23971491, block=1183499.
   *  slotsPerKesEvolution=129600 (preprod), relativePeriod=23.
   */
  object Conway2:
    val relativePeriod = 23
    val slotNo         = 23971491L
    val startKesPeriod = 161L
    val issuerVkey     = ByteVector.fromValidHex("4aa6b5dbdcd388f380141bd2cb3fa5b241340fac92c27b5469fe73eff565f467")
    val hotVkey        = ByteVector.fromValidHex("55bbc058b524a50351600ea242e7c2410136bf020c57e82a9ae7f4aa8fc4bd53")
    val ocertCounter   = 0L

    val coldSignature = ByteVector.fromValidHex(
      "0907bf340838d6a7e8005eb14f6562e7156eac0d15f1b28318ec275a6bae24cc8b5b228d0828dc0fc0b9388eb270a8bb52802ce75592b84aa488f9c28da0c70c"
    )

    val kesSignature = ByteVector.fromValidHex(
      "b42732ad68ad1d026fa64a6b495b7d627ead721a8a006d5e66474b8dbd9fa51964e3f2edeb653dff4e66b415e54ee5f30b0fa8a6780ce79374070ff429ed280731881ffb28e0f8f753f332e0a5bded5b2ee500635da2abdda5b9572448ffcaf737137891efa1403e79103559d066ccf7f7744ca3b3f5f08e8e90d6ed2f67b0ef893d7e78ad04d414f5adae25f9d684384cc3999991343e19fa1e601a1040398510436fd137de03b7b47bb1fc68f51c20e7aa2b3c5ae33562090a7e2f90a55468c9f5d7bc9e0465af512dbaef10bdc539d90f25da66d99ddb558584e8dc2b3771a41121be407da1dcfdfc5ea7275b4b1ec28501ee6b375cb392e2264e09336471cfaff50fee8073c9fd43a83c8e16567b0c96094752dfe597a13e4f457f60d057236a9d4ba127e9f1208781a7934f8a749af51335a7f2bdc6db0142ff4ab1c5cc3a5e0457aad99c69d7e12c952739b8157788792e5421081c473be36574e580335ee445a64ca663dc5f8e2ae37972d6fd49150349d7f38884934a4083025d383dfe9e5e4ee3fded389eea87cf13238233ea1888ad1915ff81e9acf21f506f8f325dccd8cfea20fb7e44412c9d49f3649f49f62096566f9703acdf5dde7d7fbd46"
    )

    val rawHeaderBody = ByteVector.fromValidHex(
      "8a1a00120f0b1a016dc6a35820758bc1310101e0f7936f86fd98c107fd8afed20a6834995ed4c24ed142c1318258204aa6b5dbdcd388f380141bd2cb3fa5b241340fac92c27b5469fe73eff565f4675820b578942b49a57735cb4d96de30faafa5fab3097389743cde5447258e902be5bb82584099f90365a06bad67307a8dc282d260a948f2166891c00fa4b897afd62d94d243a5b4d3f6f03d36434fb9f4b2cb2927ed30b6bd0bd27f835b22cb705aa2e5c03958504110edda16d5886109322503f8b564400c1cfd86abe6b1becfdcd750a6dd2f3176f1dffadb6b6e28d1b1d5850ec5780e1236306fd1db7f0a15167908765de967257dccd729d2fb95add164cb8e2103041901505820e65a86a416e6134b634b980c47481192d1e76a8dfb9271b8238b97b4ccb5503e84582055bbc058b524a50351600ea242e7c2410136bf020c57e82a9ae7f4aa8fc4bd530018a158400907bf340838d6a7e8005eb14f6562e7156eac0d15f1b28318ec275a6bae24cc8b5b228d0828dc0fc0b9388eb270a8bb52802ce75592b84aa488f9c28da0c70c820900"
    )

  // ===========================================================================
  // Standard Sum6KES wire format verification tests
  // ===========================================================================

  test("Sum6KES signature size is 448 bytes (standard wire format)") {
    assertEquals(SumKES.Sum6StandardSignatureSize, 448)
    assertEquals(Shelley1.kesSignature.size, 448L)
    assertEquals(Babbage1.kesSignature.size, 448L)
    assertEquals(Conway1.kesSignature.size, 448L)
    assertEquals(Conway2.kesSignature.size, 448L)
  }

  test("Shelley mainnet block: KES signature verifies (Sum6KES standard format)") {
    val result = SumKES.verify(
      vk = Shelley1.hotVkey,
      period = Shelley1.relativePeriod,
      message = Shelley1.rawHeaderBody,
      signature = Shelley1.kesSignature,
      depth = SumKES.CardanoDepth
    )
    assert(result, s"KES signature verification failed for Shelley block (period=${Shelley1.relativePeriod})")
  }

  test("Babbage testnet block: KES signature verifies (Sum6KES standard format)") {
    val result = SumKES.verify(
      vk = Babbage1.hotVkey,
      period = Babbage1.relativePeriod,
      message = Babbage1.rawHeaderBody,
      signature = Babbage1.kesSignature,
      depth = SumKES.CardanoDepth
    )
    assert(result, s"KES signature verification failed for Babbage block (period=${Babbage1.relativePeriod})")
  }

  test("Conway preprod block 1: KES signature verifies (Sum6KES standard format)") {
    val result = SumKES.verify(
      vk = Conway1.hotVkey,
      period = Conway1.relativePeriod,
      message = Conway1.rawHeaderBody,
      signature = Conway1.kesSignature,
      depth = SumKES.CardanoDepth
    )
    assert(result, s"KES signature verification failed for Conway block 1 (period=${Conway1.relativePeriod})")
  }

  test("Conway preprod block 2: KES signature verifies (Sum6KES standard format)") {
    val result = SumKES.verify(
      vk = Conway2.hotVkey,
      period = Conway2.relativePeriod,
      message = Conway2.rawHeaderBody,
      signature = Conway2.kesSignature,
      depth = SumKES.CardanoDepth
    )
    assert(result, s"KES signature verification failed for Conway block 2 (period=${Conway2.relativePeriod})")
  }

  // ===========================================================================
  // OCert Ed25519 verification tests
  // ===========================================================================

  test("Shelley block: OCert cold-key signature verifies") {
    val ocertMsg = Shelley1.hotVkey ++
      ByteVector.fromLong(Shelley1.ocertCounter) ++
      ByteVector.fromLong(Shelley1.startKesPeriod)
    val result = verifyEd25519(Shelley1.issuerVkey, ocertMsg, Shelley1.coldSignature)
    assert(result, "OCert Ed25519 signature verification failed for Shelley block")
  }

  test("Babbage block: OCert cold-key signature verifies") {
    val ocertMsg = Babbage1.hotVkey ++
      ByteVector.fromLong(Babbage1.ocertCounter) ++
      ByteVector.fromLong(Babbage1.startKesPeriod)
    val result = verifyEd25519(Babbage1.issuerVkey, ocertMsg, Babbage1.coldSignature)
    assert(result, "OCert Ed25519 signature verification failed for Babbage block")
  }

  test("Conway block 1: OCert cold-key signature verifies") {
    val ocertMsg = Conway1.hotVkey ++
      ByteVector.fromLong(Conway1.ocertCounter) ++
      ByteVector.fromLong(Conway1.startKesPeriod)
    val result = verifyEd25519(Conway1.issuerVkey, ocertMsg, Conway1.coldSignature)
    assert(result, "OCert Ed25519 signature verification failed for Conway block 1")
  }

  test("Conway block 2: OCert cold-key signature verifies") {
    val ocertMsg = Conway2.hotVkey ++
      ByteVector.fromLong(Conway2.ocertCounter) ++
      ByteVector.fromLong(Conway2.startKesPeriod)
    val result = verifyEd25519(Conway2.issuerVkey, ocertMsg, Conway2.coldSignature)
    assert(result, "OCert Ed25519 signature verification failed for Conway block 2")
  }

  // ===========================================================================
  // KES period range tests
  // ===========================================================================

  test("all test vectors have valid relative periods within Sum6KES range") {
    val periods = List(
      ("Shelley1", Shelley1.relativePeriod),
      ("Babbage1", Babbage1.relativePeriod),
      ("Conway1", Conway1.relativePeriod),
      ("Conway2", Conway2.relativePeriod)
    )
    periods.foreach { case (name, period) =>
      assert(period >= 0, s"$name: relativePeriod $period is negative")
      assert(period < 64, s"$name: relativePeriod $period >= 2^6 = 64")
    }
  }

  // ===========================================================================
  // Negative tests: wrong inputs should fail
  // ===========================================================================

  test("KES signature fails with wrong period") {
    val wrongPeriod = (Babbage1.relativePeriod + 1) % 64
    val result = SumKES.verify(
      vk = Babbage1.hotVkey,
      period = wrongPeriod,
      message = Babbage1.rawHeaderBody,
      signature = Babbage1.kesSignature
    )
    assert(!result, "KES verification should fail with wrong period")
  }

  test("KES signature fails with wrong message") {
    val result = SumKES.verify(
      vk = Babbage1.hotVkey,
      period = Babbage1.relativePeriod,
      message = ByteVector.fill(100)(0xaa),
      signature = Babbage1.kesSignature
    )
    assert(!result, "KES verification should fail with wrong message")
  }

  test("KES signature fails with wrong VK") {
    val result = SumKES.verify(
      vk = ByteVector.fill(32)(0x42),
      period = Babbage1.relativePeriod,
      message = Babbage1.rawHeaderBody,
      signature = Babbage1.kesSignature
    )
    assert(!result, "KES verification should fail with wrong root VK")
  }

  test("KES signature fails with tampered signature") {
    val tampered = Conway1.kesSignature.update(10, (Conway1.kesSignature(10) ^ 0xff).toByte)
    val result = SumKES.verify(
      vk = Conway1.hotVkey,
      period = Conway1.relativePeriod,
      message = Conway1.rawHeaderBody,
      signature = tampered
    )
    assert(!result, "KES verification should fail with tampered signature")
  }

  test("OCert signature fails with wrong issuer key") {
    val ocertMsg = Conway2.hotVkey ++
      ByteVector.fromLong(Conway2.ocertCounter) ++
      ByteVector.fromLong(Conway2.startKesPeriod)
    // Use a different issuer key
    val result = verifyEd25519(Conway1.issuerVkey, ocertMsg, Conway2.coldSignature)
    assert(!result, "OCert verification should fail with wrong issuer key")
  }
