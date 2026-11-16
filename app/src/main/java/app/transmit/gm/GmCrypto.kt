package app.transmit.gm

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

internal fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

/**
 * Encrypts the payloads exchanged with the phone: AES-256-CTR, then HMAC-SHA256 over ciphertext + IV.
 * Wire layout: ciphertext ‖ iv(16) ‖ hmac(32). Both keys travel to the phone inside the pairing QR code.
 */
class RequestCrypto(val aesKey: ByteArray, val hmacKey: ByteArray) {
    fun encrypt(plain: ByteArray): ByteArray {
        val iv = randomBytes(16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val body = cipher.doFinal(plain) + iv
        return body + mac(body)
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < 48) throw GmException("Encrypted payload is too short")
        val body = data.copyOfRange(0, data.size - 32)
        if (!MessageDigest.isEqual(mac(body), data.copyOfRange(data.size - 32, data.size))) throw GmException("HMAC mismatch")
        val iv = body.copyOfRange(body.size - 16, body.size)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(body, 0, body.size - 16)
    }

    private fun mac(data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }.doFinal(data)

    companion object {
        fun generate() = RequestCrypto(randomBytes(32), randomBytes(32))
    }
}

/**
 * Attachments are AES-256-GCM in chunks: a 2-byte header (0, log2 of the encrypted chunk size), then
 * per chunk nonce(12) ‖ ciphertext ‖ tag(16), with the chunk index and a last-chunk flag as AAD.
 */
object MediaCrypto {
    private const val NONCE = 12
    private const val TAG = 16
    private const val OUTGOING_CHUNK = 1 shl 15

    fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val plainChunk = OUTGOING_CHUNK - NONCE - TAG
        val out = ByteArrayOutputStream(data.size + data.size / plainChunk * (NONCE + TAG) + 64)
        out.write(0)
        out.write(15) // log2(OUTGOING_CHUNK)
        var offset = 0
        var index = 0
        while (offset < data.size) {
            val end = minOf(offset + plainChunk, data.size)
            val nonce = randomBytes(NONCE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            cipher.updateAAD(aad(index, end == data.size))
            out.write(nonce)
            out.write(cipher.doFinal(data, offset, end - offset))
            offset = end
            index++
        }
        return out.toByteArray()
    }

    /** Decrypts [input] into [output] one chunk at a time, so large videos don't have to fit in memory. */
    fun decrypt(key: ByteArray, input: InputStream, output: OutputStream) {
        val header = input.readUpTo(2)
        if (header.isEmpty()) return
        if (header.size != 2 || header[0].toInt() != 0) throw GmException("Bad media header")
        val chunkSize = 1 shl header[1].toInt()
        var current = input.readUpTo(chunkSize)
        var index = 0
        while (current.isNotEmpty()) {
            val next = input.readUpTo(chunkSize)
            if (current.size < NONCE + TAG) throw GmException("Truncated media chunk")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, current, 0, NONCE))
            cipher.updateAAD(aad(index, next.isEmpty()))
            output.write(cipher.doFinal(current, NONCE, current.size - NONCE))
            current = next
            index++
        }
    }

    private fun aad(index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(5).put(if (last) 1 else 0).putInt(index).array()
}

/** The P-256 key whose signature proves this client when refreshing the server auth token. */
class RefreshKey(private val privateKey: PrivateKey, val publicKeyX509: ByteArray) {
    val privateKeyPkcs8: ByteArray get() = privateKey.encoded

    /** ASN.1 ECDSA signature over SHA-256 of [data]. */
    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        fun generate(): RefreshKey {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
            return RefreshKey(pair.private, pair.public.encoded)
        }

        fun load(pkcs8: ByteArray, x509: ByteArray) =
            RefreshKey(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)), x509)
    }
}

/** Reads until [n] bytes or the end of the stream (InputStream.readNBytes needs API 33). */
internal fun InputStream.readUpTo(n: Int): ByteArray {
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = read(buf, got, n - got)
        if (r < 0) break
        got += r
    }
    return if (got == n) buf else buf.copyOf(got)
}

open class GmException(message: String, cause: Throwable? = null) : Exception(message, cause)
