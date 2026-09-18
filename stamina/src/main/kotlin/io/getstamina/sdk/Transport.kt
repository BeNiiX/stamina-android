// HTTP : repli d'adresses, timeout 15 s, en-têtes. Référence : docs/13-SPEC-SDK.md §2.
package io.getstamina.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResponse(val status: Int, val contentType: String, val body: String) {
    val ok: Boolean get() = status in 200..299
    val json: JSONObject? get() = try { JSONObject(body) } catch (_: Throwable) { null }
}

/**
 * Une adresse injoignable (IOException) est écartée pour la durée du process et la suivante devient
 * courante ; une erreur HTTP ne bascule pas. HttpURLConnection : aucune dépendance réseau imposée à l'app.
 */
internal class Transport(private val apiKey: String, private val endpoints: List<String>, private val userAgent: String) {
    @Volatile private var index = 0

    suspend fun request(path: String, method: String, body: JSONObject? = null): HttpResponse = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (tries in endpoints.indices) {
            val i = (index + tries) % endpoints.size
            try {
                val conn = (URL(endpoints[i] + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val contentType = (conn.getHeaderField("Content-Type") ?: "").lowercase()
                conn.disconnect()
                index = i
                return@withContext HttpResponse(status, contentType, text)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("unreachable")
    }

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://api.getstamina.io", // domaine principal
            "https://stamina-tracker.stamina.workers.dev", // repli permanent, jamais désactivé
        )
    }
}
