package pl.dubba.share.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Crypto primitives matching the Go reference. P-256 ECDH, AES-256-GCM,
 * SHA-512 key derivation. Deterministic where it matters (GCM with a fixed
 * key+nonce+plaintext produces byte-identical output to Go's Seal), which is
 * what makes cross-language test vectors possible.
 */
object Crypto {
    private const val GCM_TAG_BITS = 128 // 16-byte tag, matches Go cipher.NewGCM default
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    // --- Key derivation ---
    // sha512( sharedSecret || serverConnId(8 LE) || clientConnId(8 LE) )
    // k1 = hashed[:32] (server TX), k2 = hashed[32:] (client TX)
    data class DerivedKeys(val k1: ByteArray, val k2: ByteArray)

    fun deriveKeys(sharedSecret: ByteArray, serverConnId: Long, clientConnId: Long): DerivedKeys {
        val material = StatefulEncoder()
            .bytesNoHeader(sharedSecret)
            .u64(serverConnId)
            .u64(clientConnId)
            .toByteArray()
        val h = MessageDigest.getInstance("SHA-512").digest(material)
        return DerivedKeys(h.copyOfRange(0, 32), h.copyOfRange(32, 64))
    }

    // --- AEAD ---
    // Nonce layout: 0x00 0x00 0x00 frameType(1) counter(8 LE). Returns
    // on-wire form: nonce(12) || ciphertext || tag(16).
    fun crypt(plaintext: ByteArray, key: ByteArray, frameType: Int, counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_LEN)
        nonce[3] = frameType.toByte()
        var c = counter
        for (i in 0 until 8) {
            nonce[4 + i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    /** Returns plaintext, or null on too-short input or AEAD tag failure. */
    fun decrypt(wire: ByteArray, key: ByteArray): ByteArray? {
        if (wire.size < NONCE_LEN + TAG_LEN) return null
        val nonce = wire.copyOfRange(0, NONCE_LEN)
        val ct = wire.copyOfRange(NONCE_LEN, wire.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ct)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    // --- P-256 ECDH ---
    fun p256Params(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /** Encode a P-256 public key as 65-byte uncompressed SEC1 (0x04 || X || Y). */
    fun encodePublicKey(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return byteArrayOf(0x04) + unsignedFixed(w.affineX, 32) + unsignedFixed(w.affineY, 32)
    }

    /** Decode a 65-byte uncompressed SEC1 public key. */
    fun decodePublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0].toInt() == 0x04) { "expected 65-byte uncompressed P-256 key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), p256Params())
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    /** Import a P-256 private key from its raw 32-byte scalar. */
    fun privateKeyFromScalar(scalar: ByteArray): PrivateKey {
        val spec = ECPrivateKeySpec(BigInteger(1, scalar), p256Params())
        return KeyFactory.getInstance("EC").generatePrivate(spec)
    }

    /** ECDH shared secret = X coordinate of priv * pub, 32 bytes for P-256. */
    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    // --- Ed25519 server-identity signature ---
    //
    // The server signs a fixed concatenation that binds together the entire
    // session-crypto setup, then ships the 64-byte Ed25519 signature in the
    // HandshakeServer frame. Spec — see "Signature calculation" in
    // spec/proto.md. The client computes the same input bytes, fetches the
    // server's long-term Ed25519 public key out-of-band (HTTPS /identity),
    // and verifies. Mismatch = MITM — refuse the connection.
    //
    // Layout: clientEcdh(65) || serverEcdh(65) || clientConnId(8 LE) || serverConnId(8 LE)

    /** 146-byte canonical sign-input. Both signer (server) and verifier (client) reproduce it identically. */
    fun protoSignatureInput(
        clientEcdh: ByteArray,
        serverEcdh: ByteArray,
        clientConnId: Long,
        serverConnId: Long,
    ): ByteArray {
        require(clientEcdh.size == 65) { "clientEcdh must be 65 bytes (uncompressed P-256), got ${clientEcdh.size}" }
        require(serverEcdh.size == 65) { "serverEcdh must be 65 bytes (uncompressed P-256), got ${serverEcdh.size}" }
        return StatefulEncoder()
            .bytesNoHeader(clientEcdh)
            .bytesNoHeader(serverEcdh)
            .u64(clientConnId)
            .u64(serverConnId)
            .toByteArray()
    }

    /**
     * Verify an Ed25519 signature against the server's long-term public key.
     * Uses BouncyCastle directly — avoids the JCE provider-registration dance
     * (which is fragile on Android) and works uniformly across all API
     * levels we care about. Returns false on any failure (bad signature,
     * malformed pubkey, internal BC exception); never throws.
     */
    fun verifyEd25519(pubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (pubkey.size != 32) return false
        if (signature.size != 64) return false
        return try {
            val pub = Ed25519PublicKeyParameters(pubkey, 0)
            val signer = Ed25519Signer()
            signer.init(false, pub)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun unsignedFixed(v: BigInteger, len: Int): ByteArray {
        val raw = v.toByteArray() // big-endian, may carry a 0x00 sign byte or be short
        val out = ByteArray(len)
        val src = if (raw.size > len) raw.copyOfRange(raw.size - len, raw.size) else raw
        System.arraycopy(src, 0, out, len - src.size, src.size)
        return out
    }
}
