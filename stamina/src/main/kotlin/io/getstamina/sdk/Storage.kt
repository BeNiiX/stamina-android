// Stockage clé/valeur. Fichier SharedPreferences `io.getstamina.sdk` et clés `@stamina/*` — identiques au
// SDK React Native (docs/13-SPEC-SDK.md §4) : un appareil qui change de SDK garde son identité.
package io.getstamina.sdk

import android.content.Context
import android.content.SharedPreferences

internal object Keys {
    const val DEVICE_ID = "@stamina/device_user_id"
    const val INSTALL_SENT = "@stamina/install_registered"
    const val QUEUE = "@stamina/event_queue"
    const val LAST_ACTIVITY = "@stamina/last_activity_ts"
    const val SESSION_ID = "@stamina/session_id"
    const val SESSION_COUNT = "@stamina/session_count"
    const val SESSION_STARTED_AT = "@stamina/session_started_at"
    const val DISABLED = "@stamina/disabled"
    const val ATTRIBUTION = "@stamina/attribution"
    const val PENDING_CUSTOMER_ID = "@stamina/pending_customer_id"
    const val PENDING_ADVERTISING_ID = "@stamina/pending_advertising_id"
    const val ADVERTISING_ID_SENT = "@stamina/advertising_id_sent"

    val all = listOf(
        DEVICE_ID, INSTALL_SENT, QUEUE, LAST_ACTIVITY, SESSION_ID, SESSION_COUNT, SESSION_STARTED_AT,
        DISABLED, ATTRIBUTION, PENDING_CUSTOMER_ID, PENDING_ADVERTISING_ID, ADVERTISING_ID_SENT,
    )
}

internal interface StaminaStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

internal class PrefsStorage(context: Context) : StaminaStorage {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    companion object { const val NAME = "io.getstamina.sdk" }
}

/** Mémoire seule (tests). */
internal class InMemoryStorage : StaminaStorage {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    fun clear() = map.clear()
}
