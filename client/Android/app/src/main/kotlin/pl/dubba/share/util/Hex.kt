package pl.dubba.share.util

/**
 * Shared hex codec. Replaces hand-rolled `joinToString { "%02x".format(it) }`
 * and `digitToInt(16)`-loop variants that had crept into five different files
 * (AppFingerprint, IdentityStore, IdentityFetcher, ConfigFetcher, IdentityScreen).
 *
 * All decoders tolerate a leading `0x` / `0X` prefix and surrounding whitespace
 * - matching how Go's `%X` formatter writes pubkeys and how operators paste
 * fingerprints. They return null on any malformed input rather than throwing,
 * so callers can map to a localized error string.
 */
object Hex {

    /** Encode bytes to hex. Lowercase by default; pass [uppercase] = true for `%X`-style. */
    fun encode(bytes: ByteArray, uppercase: Boolean = false): String {
        val fmt = if (uppercase) "%02X" else "%02x"
        return bytes.joinToString("") { fmt.format(it) }
    }

    /**
     * Decode a hex string to bytes. Returns null on malformed input
     * (odd length, non-hex digits, empty after prefix-strip).
     */
    fun decode(hex: String): ByteArray? {
        val s = hex.trim().removePrefix("0x").removePrefix("0X")
        if (s.isEmpty() || s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { i ->
                ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Decode exactly [expectedBytes] bytes of hex. Returns null if the decoded
     * length doesn't match (typical use: a 32-byte Ed25519 pubkey shouldn't
     * round-trip through anything else).
     */
    fun decode(hex: String, expectedBytes: Int): ByteArray? {
        val bytes = decode(hex) ?: return null
        return if (bytes.size == expectedBytes) bytes else null
    }
}
