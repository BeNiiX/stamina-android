// Cœur du SDK : identité, install, file d'événements, sessions, synchro GAID, attribution.
// Miroir du cœur TypeScript (stamina-sdk/src/index.ts) et de StaminaKit — docs/13-SPEC-SDK.md fait foi.
//
// Concurrence : tout l'état vit sur UN thread (dispatcher mono-thread = équivalent d'un actor). Le
// stockage et la file sont SYNCHRONES (SharedPreferences), donc les sections critiques (sessions,
// enqueue) ne suspendent jamais et ne s'entrelacent pas. Seul le réseau suspend (Dispatchers.IO) ; le
// drapeau `flushing` empêche deux envois simultanés. `launch` sur ce dispatcher est FIFO : l'ordre
// d'appel de l'app est l'ordre d'envoi.
package io.getstamina.sdk

import android.app.Application
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

internal class StaminaClient(context: Context) {
    private val app = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "stamina-sdk") }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var storage: StaminaStorage = PrefsStorage(app)
    private var signals: SignalsProviding = DeviceSignals(app)
    private var transport: Transport? = null
    private var lifecycle: LifecycleObserver? = null
    internal var now: () -> Date = { Date() }

    @Volatile var configured = false; private set
    @Volatile var disabled = false; private set
    private var config: StaminaConfig? = null
    private var deviceUserId: String? = null
    private var sessionsEnabled = true

    private var flushing = false
    private var flushFailCount = 0
    private var nextFlushAllowedAt = 0L
    private var flushTimer: Job? = null
    private var installRetry: Job? = null
    private var adIdSyncing = false

    // ── hooks de test ──────────────────────────────────────────────────────

    internal fun _setStorage(s: StaminaStorage) { storage = s }
    internal fun _setSignals(s: SignalsProviding) { signals = s }
    internal fun _setNow(f: () -> Date) { now = f }

    /** Exécute `block` sur le thread du SDK, dans l'ordre des appels. */
    fun post(block: suspend StaminaClient.() -> Unit) { scope.launch { block() } }

    // ── configuration ──────────────────────────────────────────────────────

    fun configure(config: StaminaConfig) {
        val apiKey = config.apiKey.trim()
        if (apiKey.isEmpty()) {
            // Pas de clé : on ne plante JAMAIS l'app hôte, on se désactive silencieusement.
            Log.warn("configure: apiKey manquante — SDK inactif.")
            return
        }
        // configure() rappelé : on repart propre, sans doubler observateurs ni timers
        lifecycle?.stop(); lifecycle = null
        flushTimer?.cancel(); flushTimer = null
        installRetry?.cancel(); installRetry = null

        this.config = config
        val endpoints = config.endpoint?.let { listOf(it.trimEnd('/')) } ?: Transport.DEFAULT_ENDPOINTS
        transport = Transport(apiKey, endpoints, userAgent())
        disabled = storage.get(Keys.DISABLED) == "1"

        var id = config.deviceUserId ?: storage.get(Keys.DEVICE_ID) ?: ""
        if (id.isEmpty()) id = UUID.randomUUID().toString()
        storage.set(Keys.DEVICE_ID, id)
        deviceUserId = id
        sessionsEnabled = config.autoTrackSessions
        configured = true

        // install en arrière-plan avec retry/backoff
        scope.launch { registerInstall(0) }

        // Le listener sert aux sessions ET à la relecture du GAID s'il manquait à l'install.
        (app as? Application)?.let { application ->
            lifecycle = LifecycleObserver(application, onForeground = { post { onActive() } }, onBackground = { post { onBackground() } })
        }
        if (sessionsEnabled) startSessionIfNeeded()
        scope.launch { maybeSyncAdvertisingId() }

        // identité client en attente (échec précédent) → retry
        val customer = config.customerUserId?.takeIf { it.isNotEmpty() } ?: storage.get(Keys.PENDING_CUSTOMER_ID)?.takeIf { it.isNotEmpty() }
        if (customer != null) scope.launch { setCustomerUserId(customer) }
        storage.get(Keys.PENDING_ADVERTISING_ID)?.takeIf { it.isNotEmpty() }?.let { pending -> scope.launch { sendAdvertisingId(pending) } }

        scope.launch { flushQueue() }
        ensureFlushTimer()
    }

    fun currentDeviceUserId(): String {
        deviceUserId?.let { return it }
        var id = storage.get(Keys.DEVICE_ID) ?: ""
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString()
            storage.set(Keys.DEVICE_ID, id)
        }
        deviceUserId = id
        return id
    }

    private fun userAgent(): String =
        "Stamina-Android/${BuildConfig.SDK_VERSION} (${app.packageName}; Android ${Build.VERSION.RELEASE})"

    // ── événements ─────────────────────────────────────────────────────────

    fun sendEvent(name: String, subtype: String?, params: Map<String, Any?>?) {
        if (disabled) return
        enqueue(Events.make(name, subtype, params, now()))
        scope.launch { flushQueue() }
        ensureFlushTimer()
    }

    fun trackOnboardingStep(stepName: String, step: Int?, params: Map<String, Any?>?) {
        val name = Events.normalizeStep(stepName)
        if (name.isEmpty()) {
            Log.warn("trackOnboardingStep: \"$stepName\" ne contient aucun caractère valide — ignoré.")
            return
        }
        if (name != stepName) Log.warn("trackOnboardingStep: \"$stepName\" normalisé en \"$name\" — utilise directement le snake_case.")
        val extra = (params ?: emptyMap()).toMutableMap()
        extra.remove("step")
        if (step != null) extra["step"] = step
        sendEvent("onboarding", name, extra)
    }

    // ── file ───────────────────────────────────────────────────────────────

    /** Lecture tolérante : une file corrompue est remise à zéro, jamais une exception. */
    private fun readQueue(): MutableList<JSONObject> {
        val raw = storage.get(Keys.QUEUE) ?: return mutableListOf()
        try {
            val arr = JSONArray(raw)
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.opt("name") is String) out.add(o)
            }
            return out
        } catch (_: Throwable) {
            Log.warn("file d'événements corrompue — réinitialisée.")
            storage.remove(Keys.QUEUE)
            return mutableListOf()
        }
    }

    private fun writeQueue(queue: List<JSONObject>) {
        if (queue.isEmpty()) { storage.remove(Keys.QUEUE); return }
        val trimmed = queue.takeLast(MAX_QUEUE)
        storage.set(Keys.QUEUE, JSONArray(trimmed).toString())
    }

    private fun enqueue(event: JSONObject) {
        val q = readQueue()
        q.add(event)
        writeQueue(q)
    }

    private fun ensureFlushTimer() {
        if (flushTimer != null || !configured) return
        flushTimer = scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                if (readQueue().isEmpty()) { flushTimer = null; return@launch } // réarmé au prochain sendEvent
                flushQueue()
            }
        }
    }

    suspend fun flushQueue() {
        val transport = transport ?: return
        if (!configured || flushing || disabled) return
        if (now().time < nextFlushAllowedAt) return // backoff en cours
        flushing = true
        try {
            while (true) {
                val queue = readQueue()
                if (queue.isEmpty()) return
                val batch = queue.take(BATCH_SIZE)
                try {
                    val body = JSONObject().put("device_user_id", currentDeviceUserId()).put("events", JSONArray(batch))
                    val res = transport.request("/v1/sdk/events", "POST", body)
                    if (res.ok) {
                        flushFailCount = 0
                        nextFlushAllowedAt = 0
                        val sent = batch.mapNotNull { it.str("id") }.toSet()
                        // ne retire QUE les envoyés : d'autres ont pu arriver pendant l'envoi → tour suivant
                        writeQueue(readQueue().filter { it.optString("id", "") !in sent })
                        continue
                    }
                    if (!handleAuthFailure(res)) applyFlushBackoff()
                    return
                } catch (_: Throwable) {
                    applyFlushBackoff() // offline : file persistée
                    return
                }
            }
        } finally {
            flushing = false
        }
    }

    private fun applyFlushBackoff() {
        flushFailCount += 1
        val backoffMs = minOf(30_000.0 * Math.pow(2.0, (flushFailCount - 1).toDouble()), 300_000.0).toLong() // 30 s → 5 min max
        nextFlushAllowedAt = now().time + backoffMs
    }

    suspend fun flush() {
        flushFailCount = 0
        nextFlushAllowedAt = 0
        flushQueue()
    }

    /** Clé rejetée par NOTRE API (401/403 JSON `{ error }`) → kill-switch persistant. Un 403 HTML
     *  (challenge Cloudflare, proxy, portail captif) est une panne transitoire → backoff. */
    private fun handleAuthFailure(res: HttpResponse): Boolean {
        if (res.status != 401 && res.status != 403) return false
        if (!res.contentType.contains("application/json")) return false
        if (res.json?.opt("error") !is String) return false
        disabled = true
        storage.set(Keys.DISABLED, "1")
        Log.warn("clé API rejetée par le serveur — SDK désactivé jusqu'à clearData().")
        return true
    }

    // ── install ────────────────────────────────────────────────────────────

    private suspend fun registerInstall(attempt: Int) {
        val config = config ?: return
        val transport = transport ?: return
        if (!configured || disabled) return
        if (storage.get(Keys.INSTALL_SENT) == "1") return
        val s = signals.collect()
        val adId = config.advertisingId?.takeIf { it.isNotEmpty() && !isZeroAdId(it) } ?: s.advertisingId
        val extras = JSONObject()
            .put("sdk_platform", "android")
            .put("sdk_version", BuildConfig.SDK_VERSION)
            .put("app_version", s.appVersion ?: JSONObject.NULL)
            .put("app_build", s.appBuild ?: JSONObject.NULL)
            .put("att_status", s.attStatus.wire)
        val payload = JSONObject()
            .put("device_user_id", currentDeviceUserId())
            .put("os_version", s.osVersion ?: JSONObject.NULL)
            .put("device_model", s.deviceModel ?: JSONObject.NULL)
            .put("device_manufacturer", s.manufacturer ?: JSONObject.NULL)
            .put("screen_w", s.screenW ?: JSONObject.NULL)
            .put("screen_h", s.screenH ?: JSONObject.NULL)
            .put("screen_scale", s.screenScale ?: JSONObject.NULL)
            .put("color_scheme", s.colorScheme ?: JSONObject.NULL)
            .put("locale", s.locale ?: JSONObject.NULL)
            .put("timezone", s.timezone ?: JSONObject.NULL)
            .put("webview_user_agent", config.webviewUserAgent ?: s.webViewUserAgent ?: JSONObject.NULL)
            .put("install_referrer", config.installReferrer ?: s.installReferrer ?: JSONObject.NULL)
            .put("idfv", JSONObject.NULL)
            .put("adservices_token", JSONObject.NULL)
            .put("idfa", JSONObject.NULL)
            .put("gaid", adId ?: JSONObject.NULL)
            .put("customer_user_id", config.customerUserId ?: JSONObject.NULL)
            .put("extras", extras)
        try {
            val res = transport.request("/v1/sdk/install", "POST", payload)
            if (res.ok) {
                storage.set(Keys.INSTALL_SENT, "1")
                if (adId != null) storage.set(Keys.ADVERTISING_ID_SENT, adId) else scope.launch { maybeSyncAdvertisingId() }
                return
            }
            if (handleAuthFailure(res)) return
            throw IllegalStateException("install HTTP ${res.status}")
        } catch (_: Throwable) {
            if (attempt >= INSTALL_RETRY_DELAYS_S.size) return // retenté au prochain configure()
            installRetry = scope.launch {
                delay(INSTALL_RETRY_DELAYS_S[attempt] * 1000)
                registerInstall(attempt + 1)
            }
        }
    }

    // ── sessions ───────────────────────────────────────────────────────────

    fun onActive() {
        if (sessionsEnabled) startSessionIfNeeded()
        scope.launch { maybeSyncAdvertisingId() }
    }

    fun onBackground() {
        if (sessionsEnabled) endSession()
    }

    private fun startSessionIfNeeded() {
        if (disabled) return
        val t = now().time
        val last = storage.get(Keys.LAST_ACTIVITY)?.toLongOrNull() ?: 0L
        val current = storage.get(Keys.SESSION_ID)
        if (!current.isNullOrEmpty() && t - last < SESSION_TIMEOUT_MS) {
            storage.set(Keys.LAST_ACTIVITY, t.toString())
            return
        }
        val number = (storage.get(Keys.SESSION_COUNT)?.toIntOrNull() ?: 0) + 1
        val sessionId = UUID.randomUUID().toString()
        storage.set(Keys.SESSION_COUNT, number.toString())
        storage.set(Keys.SESSION_ID, sessionId)
        storage.set(Keys.SESSION_STARTED_AT, t.toString())
        storage.set(Keys.LAST_ACTIVITY, t.toString())
        sendEvent("session_start", null, mapOf("session_id" to sessionId, "session_number" to number))
    }

    private fun endSession() {
        if (disabled) return
        val sessionId = storage.get(Keys.SESSION_ID)?.takeIf { it.isNotEmpty() } ?: return
        val t = now().time
        val startedAt = storage.get(Keys.SESSION_STARTED_AT)?.toLongOrNull() ?: t
        val number = storage.get(Keys.SESSION_COUNT)?.toIntOrNull() ?: 0
        storage.set(Keys.LAST_ACTIVITY, t.toString())
        val duration = maxOf(0L, Math.round((t - startedAt) / 1000.0))
        sendEvent("session_end", null, mapOf("session_id" to sessionId, "session_number" to number, "duration_s" to duration))
    }

    // ── identifiants ───────────────────────────────────────────────────────

    /** GAID parti avec l'install ; sinon (Play Services indisponible sur le moment) relu au premier
     *  plan et rattaché à l'install — une fois, puis seulement s'il change. */
    suspend fun maybeSyncAdvertisingId(): String? {
        if (!configured || disabled || adIdSyncing || config?.advertisingId != null) return null
        if (signals.trackingStatus() != TrackingStatus.AUTHORIZED) return null
        adIdSyncing = true
        try {
            if (storage.get(Keys.INSTALL_SENT) != "1") return null
            val id = signals.advertisingId() ?: return null
            if (storage.get(Keys.ADVERTISING_ID_SENT) == id) return id
            sendAdvertisingId(id)
            return id
        } finally {
            adIdSyncing = false
        }
    }

    private suspend fun sendAdvertisingId(id: String) {
        val transport = transport ?: return
        if (id.isEmpty() || isZeroAdId(id) || disabled) return
        try {
            val res = transport.request("/v1/sdk/identify", "POST", JSONObject().put("device_user_id", currentDeviceUserId()).put("advertising_id", id))
            if (res.ok) {
                storage.set(Keys.ADVERTISING_ID_SENT, id)
                storage.remove(Keys.PENDING_ADVERTISING_ID)
            } else {
                storage.set(Keys.PENDING_ADVERTISING_ID, id)
            }
        } catch (_: Throwable) {
            storage.set(Keys.PENDING_ADVERTISING_ID, id)
        }
    }

    suspend fun requestTrackingAuthorization(): Boolean {
        val status = signals.requestTrackingAuthorization()
        if (status == TrackingStatus.AUTHORIZED) scope.launch { maybeSyncAdvertisingId() }
        return status == TrackingStatus.AUTHORIZED
    }

    fun trackingStatus(): TrackingStatus = signals.trackingStatus()

    suspend fun setCustomerUserId(customerUserId: String) {
        val transport = transport ?: return
        if (customerUserId.isEmpty() || disabled) return
        try {
            val res = transport.request("/v1/sdk/identify", "POST", JSONObject().put("device_user_id", currentDeviceUserId()).put("customer_user_id", customerUserId))
            if (res.ok) storage.remove(Keys.PENDING_CUSTOMER_ID) else storage.set(Keys.PENDING_CUSTOMER_ID, customerUserId)
        } catch (_: Throwable) {
            storage.set(Keys.PENDING_CUSTOMER_ID, customerUserId)
        }
    }

    // ── attribution ────────────────────────────────────────────────────────

    suspend fun attribution(): AttributionResult? {
        storage.get(Keys.ATTRIBUTION)?.let { cached ->
            runCatching { JSONObject(cached) }.getOrNull()?.let { AttributionResult.from(it) }?.let { return it }
        }
        return refreshAttribution()
    }

    suspend fun refreshAttribution(): AttributionResult? {
        val transport = transport ?: return null
        if (!configured) return null
        return try {
            val id = URLEncoder.encode(currentDeviceUserId(), "UTF-8")
            val res = transport.request("/v1/sdk/attribution?device_user_id=$id", "GET")
            if (!res.ok && res.status != 404) return null
            val json = res.json ?: return null
            val result = AttributionResult.from(json) ?: return null
            if (result.status == "resolved") storage.set(Keys.ATTRIBUTION, res.body)
            result
        } catch (_: Throwable) {
            null
        }
    }

    // ── divers ─────────────────────────────────────────────────────────────

    suspend fun sendTestEvent(): TestResult {
        val transport = transport ?: return TestResult(false, "Stamina.configure() must be called first")
        return try {
            val res = transport.request("/v1/sdk/test", "POST", JSONObject())
            val body = res.json
            TestResult(body?.optBoolean("success", res.ok) ?: res.ok, body?.str("message") ?: "HTTP ${res.status}")
        } catch (e: Throwable) {
            TestResult(false, e.toString())
        }
    }

    suspend fun deviceInfo(): Map<String, Any?> {
        val s = signals.collect()
        return mapOf(
            "sdk_platform" to "android", "sdk_version" to BuildConfig.SDK_VERSION, "att_status" to s.attStatus.wire,
            "os_version" to s.osVersion, "device_model" to s.deviceModel, "device_manufacturer" to s.manufacturer,
            "screen_w" to s.screenW, "screen_h" to s.screenH, "screen_scale" to s.screenScale, "color_scheme" to s.colorScheme,
            "locale" to s.locale, "timezone" to s.timezone, "webview_user_agent" to s.webViewUserAgent,
            "android_id" to s.vendorId, "install_referrer" to s.installReferrer, "has_gaid" to (s.advertisingId != null),
            "app_version" to s.appVersion, "app_build" to s.appBuild,
        )
    }

    /** Reset complet (y compris kill-switch et device id). */
    fun clearData() {
        Keys.all.forEach(storage::remove)
        deviceUserId = null
        disabled = false
        flushFailCount = 0
        nextFlushAllowedAt = 0
    }

    /** Coupe timers et observateurs (tests / réinitialisation). */
    fun destroy() {
        flushTimer?.cancel(); flushTimer = null
        installRetry?.cancel(); installRetry = null
        lifecycle?.stop(); lifecycle = null
        configured = false
    }

    /** Libère le thread du SDK (tests uniquement : en production le client vit autant que le process). */
    fun shutdown() { dispatcher.close() }

    /** Exécute `block` sur le thread du SDK (après tout ce qui a été posté avant) et rend son résultat. */
    suspend fun <T> onThread(block: suspend StaminaClient.() -> T): T = withContext(dispatcher) { block() }

    companion object {
        const val SESSION_TIMEOUT_MS = 30L * 60 * 1000
        const val FLUSH_INTERVAL_MS = 20_000L
        val INSTALL_RETRY_DELAYS_S = listOf(30L, 120L, 600L)
        const val MAX_QUEUE = 500
        const val BATCH_SIZE = 100
    }
}
