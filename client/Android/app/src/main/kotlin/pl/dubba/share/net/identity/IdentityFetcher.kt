package pl.dubba.share.net.identity

import android.content.Context
import pl.dubba.share.R
import pl.dubba.share.net.HttpJson
import pl.dubba.share.util.Hex

/**
 * Fetches the server's Ed25519 identity public key from the HTTPS-side
 * `/identity` endpoint. Expected response shape (matches Go's BasicResponse):
 *
 *   { "status": true, "result": "0x<HEX>" }
 *
 * The hex is the 32-byte Ed25519 public key. `0x` prefix is the Go `%X`
 * default; case is uppercase. We accept either with/without prefix and any
 * case to keep this tolerant.
 *
 * Transport, SSL bypass, error string resolution all live in [HttpJson];
 * this object just describes what shape `result` has and how to turn it
 * into the caller's Result type.
 */
object IdentityFetcher {

    sealed class Result {
        /** 32-byte raw Ed25519 public key. */
        data class Success(val pubkey: ByteArray) : Result()

        /** HTTP-layer failure: timeout, connection refused, non-2xx, etc. */
        data class HttpFailure(val message: String) : Result()

        /** Response was JSON but didn't match the expected shape. */
        data class BadResponse(val message: String) : Result()
    }

    /**
     * @param context any Context - used only for resolving localized error
     *   strings via `getString`. Callers should pass an Activity/Service
     *   context (or the locale-wrapped applicationContext) so the inner
     *   failure messages match the rest of the UI's language.
     * @param urlPrefix the value of Settings.urlPrefix - e.g.
     *   `http://192.168.1.42:8049/` or `https://share.example.com/`.
     * @param timeoutMs hard cap on the whole fetch.
     * @param trustAllCerts when true, HTTPS endpoints skip cert + hostname
     *   verification. Maps to Settings -> Advanced -> "Ignore SSL errors".
     */
    fun fetch(
        context: Context,
        urlPrefix: String,
        timeoutMs: Int = 5000,
        trustAllCerts: Boolean = false,
    ): Result {
        return when (val r = HttpJson.fetch(context, urlPrefix, "identity", timeoutMs, trustAllCerts)) {
            is HttpJson.Result.HttpFailure -> Result.HttpFailure(r.message)
            is HttpJson.Result.BadResponse -> Result.BadResponse(r.message)
            is HttpJson.Result.Success -> {
                val resultStr = r.body.optString("result", "")
                if (resultStr.isBlank()) {
                    Result.BadResponse(context.getString(R.string.ident_fetch_missing_result))
                } else {
                    val bytes = Hex.decode(resultStr, expectedBytes = 32)
                    if (bytes == null) {
                        // Mirror the original two-bucket error messaging:
                        // wrong length vs non-hex chars. Distinguish so the
                        // user can tell typo from drift.
                        val cleaned = resultStr.trim().removePrefix("0x").removePrefix("0X")
                        if (cleaned.length != 64) {
                            Result.BadResponse(context.getString(R.string.ident_fetch_bad_hex_length, cleaned.length))
                        } else {
                            Result.BadResponse(context.getString(R.string.ident_fetch_non_hex))
                        }
                    } else {
                        Result.Success(bytes)
                    }
                }
            }
        }
    }
}
