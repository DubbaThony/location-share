package pl.dubba.share.net

import android.content.Context
import org.json.JSONObject
import pl.dubba.share.R
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Shared HTTP-JSON fetch plumbing for the server's small `BasicResponse[T]`
 * endpoints (`/identity`, `/config`, `/gpdr-email`). Each of those previously
 * had its own ~80-line file that was 90% identical: same URL builder, same
 * trust-all-certs SSL bypass, same connection setup, same JSON envelope check.
 * This is the one place that knows about transport; per-endpoint fetchers
 * stay tiny and focus only on parsing the `result` field.
 *
 * Localized error messages all come from the `ident_fetch_*` string keys
 * that already existed for IdentityFetcher - those weren't really
 * identity-specific, they were general "I couldn't fetch JSON from the
 * server" messages.
 */
object HttpJson {

    sealed class Result {
        /**
         * Server returned a 2xx JSON body with `status: true`. The full parsed
         * body is in [body]; callers pull `result` out themselves since its
         * shape varies per endpoint.
         */
        data class Success(val body: JSONObject) : Result()

        /** Transport-layer failure (timeout, refused, non-2xx, bad TLS). */
        data class HttpFailure(val message: String) : Result()

        /** Got bytes, couldn't make sense of them as a `BasicResponse`. */
        data class BadResponse(val message: String) : Result()
    }

    /**
     * GET `<urlPrefix>/<path>`, parse the `BasicResponse` envelope, return
     * the inner [Result]. [path] is the endpoint name without a leading slash
     * (e.g. `"identity"`, `"config"`, `"gpdr-email"`).
     *
     * @param trustAllCerts when true, HTTPS endpoints skip both certificate
     *   chain validation and hostname verification. Maps to the user's
     *   Settings -> Advanced -> "Ignore SSL certificate errors" toggle. No
     *   effect on plain `http://` URLs.
     */
    fun fetch(
        context: Context,
        urlPrefix: String,
        path: String,
        timeoutMs: Int = 5000,
        trustAllCerts: Boolean = false,
    ): Result {
        if (urlPrefix.isBlank()) {
            return Result.HttpFailure(context.getString(R.string.ident_fetch_url_blank))
        }
        val url = buildUrl(urlPrefix, path)
            ?: return Result.HttpFailure(context.getString(R.string.ident_fetch_url_unparseable))

        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (trustAllCerts && this is HttpsURLConnection) {
                    sslSocketFactory = trustAllSocketFactory()
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
            }
        } catch (e: Exception) {
            return Result.HttpFailure(
                context.getString(
                    R.string.ident_fetch_connect_failed,
                    e.message ?: e.javaClass.simpleName,
                ),
            )
        }

        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Result.HttpFailure(context.getString(R.string.ident_fetch_http_status, code, url))
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseBody(context, body)
            }
        } catch (e: Exception) {
            Result.HttpFailure(
                context.getString(
                    R.string.ident_fetch_read_failed,
                    e.message ?: e.javaClass.simpleName,
                ),
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pull an optional string field out of a JSON object, treating JSON null,
     * missing key, and empty string all as Kotlin null. Useful for the
     * server's `*string` fields (e.g. `preffered_app_build_hash`,
     * `local_signer`) that come back as JSON null when the server hasn't
     * computed them.
     */
    fun optStringOrNull(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val s = obj.optString(key, "")
        return s.ifBlank { null }
    }

    private fun buildUrl(urlPrefix: String, path: String): String? {
        val trimmed = urlPrefix.trim()
        if (trimmed.isEmpty()) return null
        // urlPrefix typically ends with `/`; path may or may not have leading
        // `/`. Normalise both so we don't get `//path` or `path` (missing /).
        val base = trimmed.trimEnd('/')
        val suffix = path.trimStart('/')
        return "$base/$suffix"
    }

    private fun parseBody(context: Context, body: String): Result {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Result.BadResponse(context.getString(R.string.ident_fetch_not_json, e.message.orEmpty()))
        }
        if (!json.optBoolean("status", false)) {
            return Result.BadResponse(context.getString(R.string.ident_fetch_status_false))
        }
        return Result.Success(json)
    }

    /**
     * Permissive TrustManager / hostname verifier for the SSL-bypass branch.
     * Built fresh on every fetch so we don't accidentally cache a permissive
     * SSLContext anywhere - the cost is negligible (~1ms init). Only called
     * from the bypass code path; do NOT export.
     */
    private fun trustAllSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx.socketFactory
    }
}
