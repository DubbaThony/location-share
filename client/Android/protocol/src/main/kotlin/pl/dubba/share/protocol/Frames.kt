package pl.dubba.share.protocol

/** Frame type byte (outer envelope). Mirrors Go `frametypes.FrameTy*`. */
object FrameTy {
    const val HandshakeClient = 1
    const val HandshakeServer = 2
    const val EncryptedHandshakeClient = 3
    const val EncryptedHandshakeServer = 4
    const val Ping = 5
    const val Data = 6
    const val Ctrl = 7
}

/** Handshake status byte. Mirrors Go `frametypes.HandshakeStatus*`. */
object HandshakeStatus {
    const val OK = 0
    const val ProtoTooHigh = 254
    const val ProtoTooLow = 255
}

/** Control frame type byte. Types <= 200 require an ACK; > 200 do not. */
object CtrlTy {
    const val GetSubCount = 1
    const val ShuttingDown = 251
    const val FatalMsgError = 252
    const val FatalError = 253
    const val Bye = 254
    const val Ack = 255

    fun isAcking(t: Int): Boolean = t <= 200
}

const val PROTO_VERSION_BYTE: Int = PROTO_VERSION

// --- Frame structs (mirror server/protocol/frametypes/frames.go) ---
// ByteArray fields use reference equality on data classes; tests compare
// marshalled bytes, never frame instances, so no equals/hashCode override.

data class AbstractFrame(
    val connId: Long,
    val frameType: Int,
    val body: ByteArray,
    val crc32: Long = 0L,
)

data class ClientHandshakeFrame(
    val ecdh: ByteArray,        // 65-byte uncompressed P-256 public key
    val protoVersion: Int,
)

data class ServerHandshakeFrame(
    val ecdh: ByteArray,        // 65 bytes
    val signature: ByteArray,   // 64 bytes — Ed25519 sig over the spec's protocol-signature input
    val status: Int,
    val connectionId: Long,
)

data class EncryptedClientHandshakeFrame(
    val featFlag: Long,         // uint32
)

data class EncryptedServerHandshakeFrame(
    val featFlag: Long,         // uint32
    val publicAlias: ByteArray, // 32 ASCII bytes [a-zA-Z0-9]
)

data class PingFrame(
    val nonce: Int,             // uint16
)

data class DataFrame(
    val data: ByteArray,
)

data class CtrlFrame(
    val ctrlType: Int,
    val ctrlNonce: Int,         // uint16
    val extra: ByteArray,
)
