package pl.dubba.share.net

import pl.dubba.share.gps.GpsFix

/**
 * The set of things the sender can push to subscribed viewers. Two flavors -
 * actual position updates (optionally carrying a direction estimate) and
 * lightweight status metadata. Wire format includes a `type` discriminator
 * so the viewer can dispatch:
 *
 *   {"type":"position","lat":...,"lon":...,"speed":...,"accuracy":...,"direction":...}
 *   {"type":"meta","gps":"acquiring"|"off"}
 *
 * `direction` is optional - omitted if no source can produce one (compass not
 * ready, no movement, no previous fix).
 *
 * The [PayloadEncoder] is a swap point: [PlaintextPayloadEncoder] emits raw
 * JSON; [EncryptedPayloadEncoder] wraps another encoder and runs its output
 * through AES-256-GCM keyed by the per-session URL-fragment key. The service
 * picks one at session start, and nothing else in the pipeline knows.
 */
sealed interface SharePayload {
    data class Position(
        val fix: GpsFix,
        val directionDeg: Float? = null,
    ) : SharePayload

    data class Meta(val gps: GpsStatus) : SharePayload
}

enum class GpsStatus(val code: String) {
    /** GPS subscription is running but no fix yet (cold start, indoors, etc.). */
    ACQUIRING("acquiring"),

    /** GPS subscription has been stopped on the sender. */
    OFF("off"),
}

fun interface PayloadEncoder {
    fun encode(payload: SharePayload): ByteArray
}

/**
 * Wraps another [PayloadEncoder] and encrypts its output with AES-256-GCM
 * using the existing [pl.dubba.share.protocol.Crypto.crypt] primitive - same
 * nonce layout the outer protocol uses (`3B zero || frameType || 8B counter LE`),
 * same on-wire shape (`nonce(12) || ciphertext || tag(16)`).
 *
 * The counter starts at 0 per session and only ever increments; the key is
 * regenerated each time the user re-opens a share session, so nonce reuse is
 * structurally impossible. Receiver doesn't need to track the counter - it's
 * embedded in the nonce, which lives at the head of each frame.
 *
 * `frameType` is namespaced to `0x00` so it can never collide with an outer
 * protocol frame type (which are `0x01..0x07`); this is purely defensive -
 * the inner and outer keys are independent, so collision would be harmless,
 * but keeping the namespaces disjoint costs nothing.
 */
class EncryptedPayloadEncoder(
    private val inner: PayloadEncoder,
    private val key: ByteArray,
) : PayloadEncoder {

    private val counter = java.util.concurrent.atomic.AtomicLong(0L)

    override fun encode(payload: SharePayload): ByteArray {
        val plain = inner.encode(payload)
        return pl.dubba.share.protocol.Crypto.crypt(
            plaintext = plain,
            key = key,
            frameType = INNER_FRAME_TYPE,
            counter = counter.getAndIncrement(),
        )
    }

    companion object {
        const val INNER_FRAME_TYPE: Int = 0
    }
}

object PlaintextPayloadEncoder : PayloadEncoder {
    override fun encode(payload: SharePayload): ByteArray = when (payload) {
        is SharePayload.Position -> buildString(112) {
            append("{\"type\":\"position\",\"lat\":")
            append(payload.fix.lat)
            append(",\"lon\":")
            append(payload.fix.lon)
            append(",\"speed\":")
            append(payload.fix.speedMps ?: 0f)
            append(",\"accuracy\":")
            append(payload.fix.accuracyMeters)
            payload.directionDeg?.let {
                append(",\"direction\":")
                append(it)
            }
            append('}')
        }.toByteArray(Charsets.UTF_8)

        is SharePayload.Meta -> buildString {
            append("{\"type\":\"meta\",\"gps\":\"")
            append(payload.gps.code)
            append("\"}")
        }.toByteArray(Charsets.UTF_8)
    }
}
