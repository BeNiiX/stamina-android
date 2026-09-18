// STAMINA — SDK d'attribution Android. Façade publique.
//
//   Stamina.configure(this, "stam_android_live_…")           // Application.onCreate, non bloquant
//   Stamina.sendEvent("purchase", params = mapOf("revenue" to 9.99, "currency" to "EUR"))
//   Stamina.trackOnboardingStep("choose_goal", step = 2)
//   Stamina.linkRevenueCat(bridge)                            // identité partagée RevenueCat (suspend)
//
// Ou zéro ligne de code : <meta-data android:name="io.getstamina.API_KEY" android:value="stam_android_live_…"/>
// dans le manifest (voir StaminaInitializer).
//
// Rien d'autre à brancher : sessions, install, Install Referrer, GAID, file hors ligne, kill-switch.
package io.getstamina.sdk

import android.content.Context
import org.json.JSONObject

data class StaminaConfig(
    val apiKey: String,
    /** Force une adresse unique (désactive le repli). Tests. */
    val endpoint: String? = null,
    /** Forcer l'identifiant (sinon UUID généré et persisté au 1er lancement). À partager avec RevenueCat. */
    val deviceUserId: String? = null,
    /** Identifiant interne (opaque/hashé, jamais de PII brute). Retry automatique. */
    val customerUserId: String? = null,
    /** Sessions automatiques (session_start / session_end, timeout 30 min). Défaut true. */
    val autoTrackSessions: Boolean = true,
    /** GAID si l'app le lit déjà elle-même (sinon collecté par le SDK ; valeurs tout-à-zéro ignorées). */
    val advertisingId: String? = null,
    /** Install Referrer si l'app le lit déjà elle-même (sinon collecté par le SDK). */
    val installReferrer: String? = null,
    /** User-agent WebView si l'app le lit déjà elle-même (sinon collecté par le SDK). */
    val webviewUserAgent: String? = null,
    /** Réservé aux wrappers (Flutter, React Native) : valeur de `extras.sdk_platform`. Défaut `android`. */
    val sdkPlatform: String = "android",
)

data class AttributionResult(
    /** `resolved` | `pending` | `unknown_device` */
    val status: String,
    val isAttributed: Boolean?,
    val source: String?,
    val label: String?,
    /** `deterministic` | `probabilistic` | `organic` */
    val method: String?,
    val matchedAt: String?,
) {
    companion object {
        internal fun from(o: JSONObject): AttributionResult? {
            val status = o.optString("status", "").takeIf { it.isNotEmpty() } ?: return null
            return AttributionResult(
                status = status,
                isAttributed = if (o.has("isAttributed") && !o.isNull("isAttributed")) o.optBoolean("isAttributed") else null,
                source = o.str("source"),
                label = o.str("label"),
                method = o.str("method"),
                matchedAt = o.str("matchedAt"),
            )
        }
    }
}

data class TestResult(val success: Boolean, val message: String)

/** Pont vers RevenueCat : le SDK n'importe pas RevenueCat, l'app implémente ces 4 méthodes autour de `Purchases.sharedInstance`. */
interface StaminaPurchasesBridge {
    suspend fun isAnonymous(): Boolean
    suspend fun appUserID(): String
    suspend fun logIn(appUserID: String)
    suspend fun setAttributes(attributes: Map<String, String>)
}

data class LinkRevenueCatResult(
    val deviceUserId: String,
    val appUserId: String?,
    /** `login` (RC anonyme aliasé) · `same` (déjà le même id) · `customer` (id de l'app retenu) · `attribute` (attribut seul) */
    val method: String,
    val attributeSet: Boolean,
)

object Stamina {
    const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    @Volatile internal var client: StaminaClient? = null
    private val lock = Any()

    internal fun clientFor(context: Context): StaminaClient = synchronized(lock) {
        client ?: StaminaClient(context.applicationContext).also { client = it }
    }

    /** Tests : oublie le client courant (Robolectric crée une Application par test). */
    internal fun _resetForTests() = synchronized(lock) {
        client?.let { it.destroy(); it.shutdown() }
        client = null
    }

    private fun requireClient(): StaminaClient? = client ?: run {
        Log.warn("Stamina.configure(context, apiKey) doit être appelé d'abord.")
        null
    }

    // ── configuration ──────────────────────────────────────────────────────

    /** À appeler le plus tôt possible (Application.onCreate). Non bloquant : l'install part en arrière-plan. */
    @JvmStatic
    fun configure(context: Context, config: StaminaConfig) {
        clientFor(context).post { configure(config) }
    }

    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, apiKey: String, deviceUserId: String? = null, customerUserId: String? = null, autoTrackSessions: Boolean = true) {
        configure(context, StaminaConfig(apiKey = apiKey, deviceUserId = deviceUserId, customerUserId = customerUserId, autoTrackSessions = autoTrackSessions))
    }

    /** L'id anonyme à partager avec RevenueCat (`Purchases.logIn`). */
    suspend fun deviceUserId(): String {
        val c = requireClient() ?: return ""
        return c.onThread { currentDeviceUserId() }
    }

    // ── événements ─────────────────────────────────────────────────────────

    /** Taxonomie standard normalisée, le reste part en `custom`. `revenue` (ou `price`) + `currency` pour le revenu. */
    @JvmStatic
    @JvmOverloads
    fun sendEvent(name: String, subtype: String? = null, params: Map<String, Any?>? = null) {
        requireClient()?.post { sendEvent(name, subtype, params) }
    }

    /** Étape d'onboarding typée. Nom STABLE en snake_case ; `step` = indice de secours (1 = première). Réservés : `completed`, `skipped`. */
    @JvmStatic
    @JvmOverloads
    fun trackOnboardingStep(stepName: String, step: Int? = null, params: Map<String, Any?>? = null) {
        requireClient()?.post { trackOnboardingStep(stepName, step, params) }
    }

    /** Lie ton id utilisateur interne (opaque/hashé) à l'install. Retry auto au prochain configure(). */
    @JvmStatic
    fun setCustomerUserId(customerUserId: String) {
        requireClient()?.post { setCustomerUserId(customerUserId) }
    }

    // ── RevenueCat ─────────────────────────────────────────────────────────

    /** Lie l'identité STAMINA à RevenueCat, quel que soit l'état de l'app. Ne lève jamais.
     *  À appeler APRÈS `Purchases.configure`, et à rappeler après chaque `logIn` / `logOut`. */
    suspend fun linkRevenueCat(purchases: StaminaPurchasesBridge, logIn: Boolean = true): LinkRevenueCatResult {
        val deviceUserId = deviceUserId()
        var method = "attribute"
        var appUserId: String? = null
        try {
            val anonymous = purchases.isAnonymous()
            if (anonymous && logIn) {
                purchases.logIn(deviceUserId)
                method = "login"
            }
            appUserId = purchases.appUserID()
            if (appUserId == deviceUserId) {
                if (method != "login") method = "same"
            } else if (!appUserId.isNullOrEmpty() && !anonymous) {
                method = "customer"
                setCustomerUserId(appUserId)
            }
        } catch (e: Throwable) {
            Log.warn("linkRevenueCat: identité RevenueCat indisponible ($e)")
        }
        var attributeSet = false
        try {
            purchases.setAttributes(mapOf("stamina_device_user_id" to deviceUserId))
            attributeSet = true
        } catch (e: Throwable) {
            Log.warn("linkRevenueCat: setAttributes a échoué ($e)")
        }
        return LinkRevenueCatResult(deviceUserId, appUserId, method, attributeSet)
    }

    // ── identifiants ───────────────────────────────────────────────────────

    /** Pas de prompt sur Android : renvoie true. Présent pour la symétrie avec iOS. */
    suspend fun requestTrackingAuthorization(): Boolean = requireClient()?.onThread { requestTrackingAuthorization() } ?: false

    val trackingStatus: TrackingStatus get() = client?.trackingStatus() ?: TrackingStatus.UNAVAILABLE

    /** Facultatif : force la relecture immédiate du GAID (déjà faite à l'install et au premier plan). */
    suspend fun syncAdvertisingId(): String? {
        val c = requireClient() ?: return null
        return c.onThread { maybeSyncAdvertisingId() }
    }

    // ── attribution / diagnostic ───────────────────────────────────────────

    /** Attribution résolue de cet install (cache local, sinon fetch). */
    suspend fun attribution(): AttributionResult? = requireClient()?.onThread { attribution() }

    /** Force un refetch de l'attribution côté serveur. */
    suspend fun refreshAttribution(): AttributionResult? = requireClient()?.onThread { refreshAttribution() }

    /** Vérification de connectivité (fonctionne même SDK désactivé). */
    suspend fun sendTestEvent(): TestResult = requireClient()?.onThread { sendTestEvent() } ?: TestResult(false, "Stamina.configure() must be called first")

    /** Diagnostic : signaux collectés. */
    suspend fun deviceInfo(): Map<String, Any?> = requireClient()?.onThread { deviceInfo() } ?: emptyMap()

    /** Vide la file immédiatement (remet le backoff à zéro). */
    suspend fun flush() { requireClient()?.onThread { flush() } }

    /** Reset complet (kill-switch et device id inclus). */
    suspend fun clearData() { requireClient()?.onThread { clearData() } }

    val isInitialized: Boolean get() = client?.configured == true

    /** true si la clé API a été rejetée (401/403) — plus rien ne part jusqu'à clearData(). */
    val isDisabled: Boolean get() = client?.disabled == true

    /** Coupe timers et observateurs (tests). */
    suspend fun destroy() { client?.onThread { destroy() } }
}
