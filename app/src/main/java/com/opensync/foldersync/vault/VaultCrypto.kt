package com.opensync.foldersync.vault

import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives for the file vault. Nothing here persists a passphrase: the passphrase derives a
 * key-encryption-key (PBKDF2) that only wraps a random 256-bit master key. Files are encrypted with
 * AES-256-GCM in 1 MiB chunks (each with its own tag + position bound as AAD) so large files stream
 * without loading whole into memory and any tampering fails loudly on decrypt.
 */
object VaultCrypto {
    const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12
    const val SALT_LEN = 16
    private const val CHUNK = 1 shl 20 // 1 MiB plaintext per chunk
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun randomAesKey(): SecretKey = SecretKeySpec(randomBytes(KEY_BITS / 8), "AES")

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Wrap small data (the master key, the index) with GCM; output is nonce || ciphertext || tag. */
    fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    /** Reverse of [seal]; throws AEADBadTagException on a wrong key or tampering. */
    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        val nonce = sealed.copyOfRange(0, NONCE_LEN)
        val ct = sealed.copyOfRange(NONCE_LEN, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ct)
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Encrypt [input] to [output] as: magic || baseNonce || (len:4, chunk)* . Returns plaintext bytes. */
    fun encryptStream(key: SecretKey, input: InputStream, output: OutputStream): Long {
        output.write(MAGIC)
        val baseNonce = randomBytes(NONCE_LEN)
        output.write(baseNonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val buf = ByteArray(CHUNK)
        var counter = 0
        var total = 0L
        while (true) {
            val n = fill(input, buf)
            if (n <= 0) break
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce(baseNonce, counter)))
            cipher.updateAAD(intBytes(counter))
            val ct = cipher.doFinal(buf, 0, n)
            writeInt(output, ct.size)
            output.write(ct)
            total += n
            counter++
            if (n < buf.size) break
        }
        return total
    }

    /** Decrypt a blob produced by [encryptStream]. Throws on tamper / truncation / wrong key. */
    fun decryptStream(key: SecretKey, input: InputStream, output: OutputStream): Long {
        val magic = readN(input, MAGIC.size) ?: throw IllegalStateException("empty vault blob")
        if (!magic.contentEquals(MAGIC)) throw IllegalStateException("bad vault blob header")
        val baseNonce = readN(input, NONCE_LEN) ?: throw IllegalStateException("truncated vault blob")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var counter = 0
        var total = 0L
        while (true) {
            val lenBytes = readN(input, 4) ?: break // clean end of stream
            val len = beInt(lenBytes)
            if (len <= 0 || len > CHUNK + 64) throw IllegalStateException("corrupt vault chunk")
            val ct = readN(input, len) ?: throw IllegalStateException("truncated vault chunk")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce(baseNonce, counter)))
            cipher.updateAAD(intBytes(counter))
            val pt = cipher.doFinal(ct)
            output.write(pt)
            total += pt.size
            counter++
        }
        return total
    }

    private fun chunkNonce(base: ByteArray, counter: Int): ByteArray = base.copyOf().also {
        it[8] = (counter ushr 24).toByte()
        it[9] = (counter ushr 16).toByte()
        it[10] = (counter ushr 8).toByte()
        it[11] = counter.toByte()
    }

    private fun intBytes(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun beInt(b: ByteArray) =
        ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)

    private fun writeInt(out: OutputStream, v: Int) = out.write(intBytes(v))

    /** Read up to buf.size bytes, looping past short reads; returns count (0 at EOF). */
    private fun fill(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /** Read exactly n bytes, or null at a clean EOF on the very first byte. */
    private fun readN(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return if (off == 0) null else throw IllegalStateException("unexpected EOF")
            off += r
        }
        return buf
    }
}
