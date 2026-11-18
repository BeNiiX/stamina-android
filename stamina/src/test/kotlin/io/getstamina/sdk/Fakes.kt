package io.getstamina.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Signaux pilotables : GAID change en cours de test, le reste imite un Android. */
internal class FakeSignals : SignalsProviding {
    var att = TrackingStatus.AUTHORIZED
    var adId: String? = "AAAAAAAA-1111-2222-3333-444444444444"
    var installReferrer: String? = "utm_source=stamina&slug=conf&click_id=c1"

    override suspend fun collect() = CollectedSignals(
        osVersion = "15", deviceModel = "SM-S918B", manufacturer = "samsung",
        screenW = 412, screenH = 915, screenScale = 2.625, colorScheme = "light",
        locale = "fr-FR", timezone = "Europe/Paris", appVersion = "1.0.0", appBuild = "1", environment = "production", bundleId = "io.getstamina.conformance",
        webViewUserAgent = "Mozilla/5.0 (conformance WebView)", vendorId = "ANDROID-ID-CONFORMANCE",
        installReferrer = installReferrer, attStatus = att, advertisingId = advertisingId(),
    )

    override fun trackingStatus() = att
    override suspend fun requestTrackingAuthorization() = att
    override suspend fun advertisingId(): String? = if (att == TrackingStatus.AUTHORIZED) adId else null
}

/** Client HTTP du serveur de conformité (../../stamina-sdk-conformance/server.js). */
internal object ConformanceServer {
    val base: String = System.getenv("STAMINA_CONFORMANCE_URL") ?: "http://127.0.0.1:4999"

    fun reachable(): Boolean = try { get("/__health"); true } catch (_: Throwable) { false }
    fun reset() { post("/__reset", "{}") }
    fun mode(mode: String) { post("/__mode", """{"mode":"$mode"}""") }

    fun calls(path: String? = null): List<JSONObject> {
        val arr = JSONArray(get("/__calls" + (path?.let { "?path=$it" } ?: "")))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun events(): List<JSONObject> = calls("/v1/sdk/events").flatMap { c ->
        val evs = c.getJSONObject("body").getJSONArray("events")
        (0 until evs.length()).map { evs.getJSONObject(it) }
    }

    fun body(call: JSONObject): JSONObject = call.getJSONObject("body")

    private fun get(path: String): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 2000; conn.readTimeout = 2000
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun post(path: String, body: String) {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
    }
}
