package pl.dubba.share.protocol

import java.util.zip.CRC32

/**
 * Frame marshaller — pure Kotlin port of server/protocol/marshaller/marshaller.go.
 * Plaintext frames marshal/unmarshal directly; encrypted frames take the AES-256-GCM
 * key + counter (caller owns the per-direction counter). Unmarshal returns null on a
 * malformed frame, bad CRC, or AEAD tag failure.
 *
 * Java's java.util.zip.CRC32 uses the IEEE polynomial — identical to Go's
 * crc32.ChecksumIEEE, so CRCs match across the two implementations.
 */
object Marshaller {

    // --- Outer envelope ---

    fun marshalAbstractFrame(f: AbstractFrame): ByteArray {
        val buf = StatefulEncoder()
            .u64(f.connId)
            .u8(f.frameType)
            .bytesNoHeader(f.body)
            .u32(0L) // CRC placeholder
            .toByteArray()
        val crc = CRC32().apply { update(buf) }.value
        val n = buf.size
        buf[n - 4] = (crc and 0xFF).toByte()
        buf[n - 3] = ((crc ushr 8) and 0xFF).toByte()
        buf[n - 2] = ((crc ushr 16) and 0xFF).toByte()
        buf[n - 1] = ((crc ushr 24) and 0xFF).toByte()
        return buf
    }

    fun unmarshalAbstractFrame(data: ByteArray): AbstractFrame? {
        if (data.size < 13) return null
        val n = data.size
        val storedCrc = (data[n - 4].toLong() and 0xFF) or
            ((data[n - 3].toLong() and 0xFF) shl 8) or
            ((data[n - 2].toLong() and 0xFF) shl 16) or
            ((data[n - 1].toLong() and 0xFF) shl 24)
        val crc = CRC32()
        crc.update(data, 0, n - 4)
        crc.update(byteArrayOf(0, 0, 0, 0))
        if (crc.value != storedCrc) return null

        val d = StatefulDecoder(data.copyOfRange(0, n - 4))
        val connId = d.u64()
        val frameType = d.u8()
        val body = d.bytesNoHeader(n - 4 - 9)
        if (!d.ok) return null
        return AbstractFrame(connId, frameType, body, storedCrc)
    }

    /** Frame-type binding check: encrypted frames carry their type at nonce byte 3. */
    fun validateFrameType(f: AbstractFrame): Boolean {
        if (f.frameType == FrameTy.HandshakeClient || f.frameType == FrameTy.HandshakeServer) return true
        return f.body.size > 12 && (f.body[3].toInt() and 0xFF) == f.frameType
    }

    // --- Plaintext handshake frames ---

    fun marshalHandshakeClient(f: ClientHandshakeFrame): ByteArray =
        StatefulEncoder()
            .bytesNoHeader(f.ecdh)
            .u8(f.protoVersion)
            .toByteArray()

    fun unmarshalHandshakeClient(data: ByteArray): ClientHandshakeFrame? {
        val d = StatefulDecoder(data)
        val ecdh = d.bytesNoHeader(65)
        val ver = d.u8()
        return if (d.ok) ClientHandshakeFrame(ecdh, ver) else null
    }

    fun marshalHandshakeServer(f: ServerHandshakeFrame): ByteArray =
        StatefulEncoder()
            .bytesNoHeader(f.ecdh)
            .bytesNoHeader(f.signature)
            .u8(f.status)
            .u64(f.connectionId)
            .toByteArray()

    fun unmarshalHandshakeServer(data: ByteArray): ServerHandshakeFrame? {
        val d = StatefulDecoder(data)
        val ecdh = d.bytesNoHeader(65)
        val signature = d.bytesNoHeader(64)
        val status = d.u8()
        val connId = d.u64()
        return if (d.ok) ServerHandshakeFrame(ecdh, signature, status, connId) else null
    }

    // --- Encrypted frames ---

    fun marshalEncryptedHandshakeClient(f: EncryptedClientHandshakeFrame, key: ByteArray, counter: Long): ByteArray {
        val body = StatefulEncoder().u32(f.featFlag).toByteArray()
        return Crypto.crypt(body, key, FrameTy.EncryptedHandshakeClient, counter)
    }

    fun unmarshalEncryptedHandshakeClient(data: ByteArray, key: ByteArray): EncryptedClientHandshakeFrame? {
        val plain = Crypto.decrypt(data, key) ?: return null
        val d = StatefulDecoder(plain)
        val ff = d.u32()
        return if (d.ok) EncryptedClientHandshakeFrame(ff) else null
    }

    fun marshalEncryptedHandshakeServer(f: EncryptedServerHandshakeFrame, key: ByteArray, counter: Long): ByteArray {
        val body = StatefulEncoder()
            .u32(f.featFlag)
            .bytesNoHeader(f.publicAlias)
            .toByteArray()
        return Crypto.crypt(body, key, FrameTy.EncryptedHandshakeServer, counter)
    }

    fun unmarshalEncryptedHandshakeServer(data: ByteArray, key: ByteArray): EncryptedServerHandshakeFrame? {
        val plain = Crypto.decrypt(data, key) ?: return null
        val d = StatefulDecoder(plain)
        val ff = d.u32()
        val alias = d.bytesNoHeader(32)
        return if (d.ok) EncryptedServerHandshakeFrame(ff, alias) else null
    }

    fun marshalPing(f: PingFrame, key: ByteArray, counter: Long): ByteArray {
        val body = StatefulEncoder().u16(f.nonce).toByteArray()
        return Crypto.crypt(body, key, FrameTy.Ping, counter)
    }

    fun unmarshalPing(data: ByteArray, key: ByteArray): PingFrame? {
        val plain = Crypto.decrypt(data, key) ?: return null
        val d = StatefulDecoder(plain)
        val nonce = d.u16()
        return if (d.ok) PingFrame(nonce) else null
    }

    fun marshalData(f: DataFrame, key: ByteArray, counter: Long): ByteArray {
        val body = StatefulEncoder().bytes(f.data).toByteArray()
        return Crypto.crypt(body, key, FrameTy.Data, counter)
    }

    fun unmarshalData(data: ByteArray, key: ByteArray): DataFrame? {
        val plain = Crypto.decrypt(data, key) ?: return null
        val d = StatefulDecoder(plain)
        val payload = d.bytes()
        return if (d.ok) DataFrame(payload) else null
    }

    fun marshalCtrl(f: CtrlFrame, key: ByteArray, counter: Long): ByteArray {
        val body = StatefulEncoder()
            .u8(f.ctrlType)
            .u16(f.ctrlNonce)
            .bytes(f.extra)
            .toByteArray()
        return Crypto.crypt(body, key, FrameTy.Ctrl, counter)
    }

    fun unmarshalCtrl(data: ByteArray, key: ByteArray): CtrlFrame? {
        val plain = Crypto.decrypt(data, key) ?: return null
        val d = StatefulDecoder(plain)
        val ctrlType = d.u8()
        val ctrlNonce = d.u16()
        val extra = d.bytes()
        return if (d.ok) CtrlFrame(ctrlType, ctrlNonce, extra) else null
    }
}
