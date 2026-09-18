// Signaux d'appareil et d'attribution — même collecte que le module natif du SDK React Native
// (android/…/StaminaNativeModule.kt), sans l'enveloppe Expo. Référence : docs/13-SPEC-SDK.md §3 et §8.
package io.getstamina.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.webkit.WebSettings
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/** Statut ATT (iOS). Toujours `authorized` sur Android : le consentement se joue via Limit Ad Tracking. */
enum class TrackingStatus(val wire: String) {
    AUTHORIZED("authorized"), DENIED("denied"), RESTRICTED("restricted"), NOT_DETERMINED("not_determined"), UNAVAILABLE("unavailable")
}

internal data class CollectedSignals(
    var osVersion: String? = null,
    var deviceModel: String? = null,
    var manufacturer: String? = null,
    var screenW: Int? = null,
    var screenH: Int? = null,
    var screenScale: Double? = null,
    var colorScheme: String? = null,
    var locale: String? = null,
    var timezone: String? = null,
    var appVersion: String? = null,
    var appBuild: String? = null,
    var bundleId: String? = null,
    var webViewUserAgent: String? = null,
    var vendorId: String? = null,
    var installReferrer: String? = null,
    var attStatus: TrackingStatus = TrackingStatus.AUTHORIZED,
    var advertisingId: String? = null,
)

/** Abstraction des signaux : `DeviceSignals` en production, un faux dans les tests de conformité. */
internal interface SignalsProviding {
    suspend fun collect(): CollectedSignals
    fun trackingStatus(): TrackingStatus
    suspend fun requestTrackingAuthorization(): TrackingStatus
    /** GAID si le suivi n'est pas limité et non tout-à-zéro, sinon null. */
    suspend fun advertisingId(): String?
}

internal class DeviceSignals(context: Context) : SignalsProviding {
    private val context = context.applicationContext

    override suspend fun collect(): CollectedSignals {
        val s = CollectedSignals()
        s.osVersion = Build.VERSION.RELEASE
        s.deviceModel = Build.MODEL
        s.manufacturer = Build.MANUFACTURER
        val metrics = context.resources.displayMetrics
        if (metrics.density > 0) {
            s.screenW = Math.round(metrics.widthPixels / metrics.density)
            s.screenH = Math.round(metrics.heightPixels / metrics.density)
            s.screenScale = metrics.density.toDouble()
        }
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        s.colorScheme = if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        s.locale = Locale.getDefault().toLanguageTag()
        s.timezone = TimeZone.getDefault().id
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            s.appVersion = info.versionName
            @Suppress("DEPRECATION")
            s.appBuild = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()).toString()
        } catch (_: PackageManager.NameNotFoundException) {}
        s.bundleId = context.packageName
        s.webViewUserAgent = withTimeoutOrNull(4_000) { withContext(Dispatchers.IO) { runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull() } }
        s.vendorId = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        s.installReferrer = withTimeoutOrNull(2_500) { readInstallReferrer() }
        s.attStatus = TrackingStatus.AUTHORIZED
        s.advertisingId = withTimeoutOrNull(3_000) { advertisingId() }
        return s
    }

    override fun trackingStatus(): TrackingStatus = TrackingStatus.AUTHORIZED

    override suspend fun requestTrackingAuthorization(): TrackingStatus = TrackingStatus.AUTHORIZED

    /** GAID — lu hors main thread (Google l'exige), nul si suivi limité ou Play Services absent. */
    override suspend fun advertisingId(): String? = withContext(Dispatchers.IO) {
        try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
            val id = info.id ?: return@withContext null
            if (info.isLimitAdTrackingEnabled || isZeroAdId(id)) null else id
        } catch (_: Throwable) {
            null
        }
    }

    /** Referrer posé par le Play Store à l'installation (slug + click_id du lien de tracking). */
    private suspend fun readInstallReferrer(): String? = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(context).build()
        var settled = false
        fun settle(value: String?) {
            if (!settled) { settled = true; if (cont.isActive) cont.resume(value) }
            try { client.endConnection() } catch (_: Throwable) {}
        }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) { settle(null); return }
                    settle(try { client.installReferrer.installReferrer } catch (_: Throwable) { null })
                }
                override fun onInstallReferrerServiceDisconnected() { settle(null) }
            })
        } catch (_: Throwable) {
            settle(null)
        }
        cont.invokeOnCancellation { try { client.endConnection() } catch (_: Throwable) {} }
    }
}

internal object Log {
    fun warn(message: String) { android.util.Log.w("STAMINA", message) }
}
