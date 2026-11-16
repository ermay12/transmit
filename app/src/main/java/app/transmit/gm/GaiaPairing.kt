package app.transmit.gm

import app.transmit.gm.proto.EcP256PublicKey
import app.transmit.gm.proto.GaiaPairingResponseContainer
import app.transmit.gm.proto.GenericPublicKey
import app.transmit.gm.proto.PublicKeyType
import app.transmit.gm.proto.Ukey2ClientFinished
import app.transmit.gm.proto.Ukey2ClientInit
import app.transmit.gm.proto.Ukey2HandshakeCipher
import app.transmit.gm.proto.Ukey2Message
import app.transmit.gm.proto.Ukey2ServerInit
import com.google.protobuf.ByteString
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One attempt at pairing through the user's Google account: a UKEY2 handshake with the phone,
 * relayed by Google. Both sides derive the same emoji from the shared key; the user confirms it on
 * the phone, which proves nobody sits in between. Port of libgm's pair_google.go.
 */
internal class GaiaPairingSession(val destRegId: String) {
    val id: String = UUID.randomUUID().toString()
    val startMs = System.currentTimeMillis()
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    lateinit var initPayload: ByteArray
        private set
    lateinit var finishPayload: ByteArray
        private set
    private var nextKey: ByteArray? = null
    var keyDerivationVersion = 0
        private set

    /** Builds the CLIENT_INIT message (sent first) and the CLIENT_FINISH it commits to. */
    fun prepare(): ByteArray {
        val pub = keyPair.public as ECPublicKey
        val publicKey = GenericPublicKey.newBuilder()
            .setType(PublicKeyType.EC_P256)
            .setEcP256PublicKey(
                EcP256PublicKey.newBuilder()
                    .setX(ByteString.copyFrom(byteArrayOf(0) + unsigned32(pub.w.affineX)))
                    .setY(ByteString.copyFrom(byteArrayOf(0) + unsigned32(pub.w.affineY))),
            )
            .build()
        finishPayload = Ukey2Message.newBuilder()
            .setMessageType(Ukey2Message.Type.CLIENT_FINISH)
            .setMessageData(Ukey2ClientFinished.newBuilder().setPublicKey(publicKey).build().toByteString())
            .build().toByteArray()
        val commitment = MessageDigest.getInstance("SHA-512").digest(finishPayload)
        val init = Ukey2ClientInit.newBuilder()
            .setVersion(1)
            .setRandom(ByteString.copyFrom(randomBytes(32)))
            .addCipherCommitments(
                Ukey2ClientInit.CipherCommitment.newBuilder()
                    .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                    .setCommitment(ByteString.copyFrom(commitment)),
            )
            .setNextProtocol("AES_256_CBC-HMAC_SHA256")
            .build()
        initPayload = Ukey2Message.newBuilder()
            .setMessageType(Ukey2Message.Type.CLIENT_INIT)
            .setMessageData(init.toByteString())
            .build().toByteArray()
        return initPayload
    }

    /** Completes the key exchange with the phone's SERVER_INIT; returns the emoji to confirm. */
    fun processServerInit(msg: GaiaPairingResponseContainer): String {
        val ukey = Ukey2Message.parseFrom(msg.data)
        if (ukey.messageType != Ukey2Message.Type.SERVER_INIT) throw GmException("Unexpected pairing message ${ukey.messageType}")
        val server = Ukey2ServerInit.parseFrom(ukey.messageData)
        if (server.version != 1) throw GmException("Unsupported pairing version ${server.version}")
        if (server.handshakeCipher != Ukey2HandshakeCipher.P256_SHA512) throw GmException("Unsupported pairing cipher ${server.handshakeCipher}")
        if (server.random.size() != 32) throw GmException("Malformed pairing message")
        val point = server.publicKey.ecP256PublicKey
        val params = (keyPair.public as ECPublicKey).params
        val serverKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(coordinate(point.x.toByteArray()), coordinate(point.y.toByteArray())), params),
        )
        val dh = KeyAgreement.getInstance("ECDH").apply {
            init(keyPair.private)
            doPhase(serverKey, true)
        }.generateSecret()
        val shared = MessageDigest.getInstance("SHA-256").digest(dh)
        val authInfo = initPayload + msg.data.toByteArray()
        val auth = hkdf(shared, "UKEY2 v1 auth".toByteArray(), authInfo)
        nextKey = hkdf(shared, "UKEY2 v1 next".toByteArray(), authInfo)
        keyDerivationVersion = msg.confirmedKeyDerivationVersion
        return emojiFor(auth, msg.confirmedVerificationCodeVersion)
    }

    /** The AES and HMAC keys for requests, once the phone accepted the pairing. */
    fun requestCrypto(): RequestCrypto =
        requestCrypto(nextKey ?: throw IllegalStateException("Handshake not finished"), keyDerivationVersion)

    companion object {
        /** The emoji for a UKEY2 auth string: its first four bytes index the version's list. */
        fun emojiFor(auth: ByteArray, version: Int): String {
            val list = when (version) {
                0 -> PairingEmoji.V0
                1 -> PairingEmoji.V1
                else -> throw GmException("Unsupported verification code version $version")
            }
            val n = ByteBuffer.wrap(auth).int.toLong() and 0xffffffffL
            return list[(n % list.size).toInt()]
        }

        fun requestCrypto(nextKey: ByteArray, version: Int): RequestCrypto {
            val client = hkdf(nextKey, PairingEmoji.ENCRYPTION_KEY_INFO, "client".toByteArray())
            val server = hkdf(nextKey, PairingEmoji.ENCRYPTION_KEY_INFO, "server".toByteArray())
            return when (version) {
                0 -> RequestCrypto(client, server)
                1 -> {
                    // libgm's byteHash is Java's Arrays.hashCode, ordering the two keys.
                    val (first, second) = if (client.contentHashCode() < server.contentHashCode()) client to server else server to client
                    val hash = MessageDigest.getInstance("SHA-256").digest(PairingEmoji.ENCRYPTION_KEY_INFO + first + second)
                    RequestCrypto(
                        hkdf(hash, "Ditto salt 1".toByteArray(), "Ditto info 1".toByteArray()),
                        hkdf(hash, "Ditto salt 2".toByteArray(), "Ditto info 2".toByteArray()),
                    )
                }
                else -> throw GmException("Unsupported key derivation version $version")
            }
        }

        /** HKDF-SHA256 with a 32-byte output. */
        fun hkdf(key: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(key)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            return mac.doFinal(info + byteArrayOf(1))
        }

        private fun unsigned32(n: BigInteger): ByteArray {
            val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            require(raw.size <= 32)
            return ByteArray(32 - raw.size) + raw
        }

        /** Coordinates arrive as big-endian two's complement, possibly with a leading zero byte. */
        private fun coordinate(bytes: ByteArray): BigInteger {
            val trimmed = if (bytes.size == 33 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, 33) else bytes
            if (trimmed.size > 32) throw GmException("Malformed pairing key")
            return BigInteger(1, trimmed)
        }
    }
}
