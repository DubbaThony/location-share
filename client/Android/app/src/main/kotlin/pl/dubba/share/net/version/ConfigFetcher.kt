package pl.dubba.share.net.version

import android.content.Context
import pl.dubba.share.R
import pl.dubba.share.net.HttpJson
import pl.dubba.share.util.Hex

/**
 * Fetches the combined server config from the HTTPS-side `/config` endpoint.
 * Expected response shape (matches Go's `ifaces.AppConfig` wrapped in
 * `BasicResponse`):
 *
 *   {
 *     "status": true,
 *     "result": {
 *       "identity":                 "0x<32-byte-ed25519-pubkey-hex>",
 *       "preffered_app_build_hash": "<sha512-hex>" | null,
 *       "local_signer":             "<raw-pkcs7-cert-blob-hex>" | null
 *     }
 *   }
 *
 * Both `preffered_app_build_hash` and `local_signer` are nullable on the
 * server (returned as `null` when the server's `embed.FS` doesn't carry an
 * APK or the cert read fails). Callers MUST treat null-or-missing as "the
 * server didn't publish this fingerprint" rather than "fingerprint mismatch".
 *
 * Identity is mandatory; if it's missing or malformed we surface a BadResponse.
 *
 * Transport + SSL bypass live in [HttpJson]; identity hex parsing in [Hex].
 */
object ConfigFetcher {

    sealed class Result {
        /**
         * @param identityPubkey  32-byte raw Ed25519 public key.
         * @param apkSha512Hex    Server's SHA-512 of its embedded APK, lowercase
         *                        hex without `0x` prefix. Null when the server
         *                        didn't publish one.
         * @param signerBlobHex   Server's raw PKCS#7 SignedData blob (the bytes
         *                        of `META-INF/SHARE.RSA` from the embedded APK),
         *                        as lowercase hex. Hand straight to
         *                        [AppFingerprint.parseServerSignerSha256] to get
         *                        the comparable fingerprint. Null when the
         *                        server didn't publish one.
         */
        data class Success(
            val identityPubkey: ByteArray,
            val apkSha512Hex: String?,
            val signerBlobHex: String?,
        ) : Result()

        data class HttpFailure(val message: String) : Result()
        data class BadResponse(val message: String) : Result()
    }

    fun fetch(
        context: Context,
        urlPrefix: String,
        timeoutMs: Int = 5000,
        trustAllCerts: Boolean = false,
    ): Result {
        return when (val r = HttpJson.fetch(context, urlPrefix, "config", timeoutMs, trustAllCerts)) {
            is HttpJson.Result.HttpFailure -> Result.HttpFailure(r.message)
            is HttpJson.Result.BadResponse -> Result.BadResponse(r.message)
            is HttpJson.Result.Success -> parseConfigResult(context, r.body)
        }
    }

    private fun parseConfigResult(context: Context, body: org.json.JSONObject): Result {
        val result = body.optJSONObject("result")
            ?: return Result.BadResponse(context.getString(R.string.ident_fetch_missing_result))

        val identityStr = result.optString("identity", "")
        if (identityStr.isBlank()) {
            return Result.BadResponse(context.getString(R.string.ident_fetch_missing_result))
        }
        val identityBytes = Hex.decode(identityStr, expectedBytes = 32)
            ?: run {
                val cleaned = identityStr.trim().removePrefix("0x").removePrefix("0X")
                return if (cleaned.length != 64) {
                    Result.BadResponse(context.getString(R.string.ident_fetch_bad_hex_length, cleaned.length))
                } else {
                    Result.BadResponse(context.getString(R.string.ident_fetch_non_hex))
                }
            }

        return Result.Success(
            identityPubkey = identityBytes,
            apkSha512Hex = HttpJson.optStringOrNull(result, "preffered_app_build_hash"),
            signerBlobHex = HttpJson.optStringOrNull(result, "local_signer"),
        )
    }
}
