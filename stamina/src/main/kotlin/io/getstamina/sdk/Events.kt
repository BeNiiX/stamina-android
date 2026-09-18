// Événements : normalisation des noms et des étapes, revenu, JSON. Référence : docs/13-SPEC-SDK.md §6.
package io.getstamina.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal object Events {
    val standard = setOf(
        "session_start", "session_end", "onboarding", "login", "sign_up", "register", "purchase", "subscribe",
        "start_trial", "add_payment_info", "add_to_cart", "add_to_wishlist",
        "initiate_checkout", "view_content", "view_item", "search", "share",
        "tutorial_complete", "achieve_level", "level_start", "level_complete",
        "test", "custom",
    )

    /** minuscules, trim, `-` → `_` */
    fun normalizeName(raw: String): String = raw.lowercase().trim().replace('-', '_')

    /** Même règle que la base : minuscules, espaces/tirets → `_`, `[a-z0-9_]`, 40 car. */
    fun normalizeStep(raw: String): String =
        raw.lowercase().trim()
            .replace(Regex("[\\s-]+"), "_")
            .replace(Regex("[^a-z0-9_]"), "")
            .take(40)

    /** `params.revenue` numérique, sinon `params.price` (nombre ou chaîne numérique). */
    fun coerceRevenue(params: Map<String, Any?>?): Double? {
        numeric(params?.get("revenue"))?.let { return it }
        when (val p = params?.get("price")) {
            is Number -> numeric(p)?.let { return it }
            is String -> p.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
        }
        return null
    }

    private fun numeric(v: Any?): Double? = when (v) {
        is Boolean -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        else -> null
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun isoString(date: Date): String = synchronized(iso) { iso.format(date) }

    /** Construit l'événement tel qu'il part sur le fil (mêmes clés que le cœur TypeScript). */
    fun make(name: String, subtype: String?, params: Map<String, Any?>?, now: Date): JSONObject {
        val normalized = normalizeName(name)
        val isStandard = normalized in standard
        val rest = (params ?: emptyMap()).toMutableMap()
        rest.remove("revenue")
        rest.remove("price")
        val currency = rest.remove("currency") as? String
        val revenue = coerceRevenue(params)

        val ev = JSONObject()
        ev.put("id", UUID.randomUUID().toString())
        ev.put("name", if (isStandard) normalized else "custom")
        ev.put("ts", isoString(now))
        val sub: String? = if (normalized == "onboarding" && !subtype.isNullOrEmpty()) normalizeStep(subtype)
        else subtype ?: if (isStandard) null else normalized
        ev.put("subtype", sub ?: JSONObject.NULL)
        if (revenue != null) ev.put("revenue", revenue)
        if (currency != null) ev.put("currency", currency.uppercase())
        if (rest.isNotEmpty()) ev.put("params", Json.sanitize(rest))
        return ev
    }
}

/** Valeurs JSON sûres : tout ce qui n'est pas sérialisable devient sa description. */
internal object Json {
    fun sanitize(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, sanitizeValue(v))
        return o
    }

    fun sanitizeValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> v
        is String, is Boolean -> v
        is Double -> if (v.isFinite()) v else JSONObject.NULL
        is Float -> if (v.isFinite()) v.toDouble() else JSONObject.NULL
        is Number -> v
        is Date -> Events.isoString(v)
        is Map<*, *> -> sanitize(v.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> JSONArray().also { arr -> v.forEach { arr.put(sanitizeValue(it)) } }
        is Array<*> -> JSONArray().also { arr -> v.forEach { arr.put(sanitizeValue(it)) } }
        else -> v.toString()
    }

    fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k ->
        when (val v = o.opt(k)) {
            JSONObject.NULL -> null
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { i -> val x = v.opt(i); if (x is JSONObject) toMap(x) else x }
            else -> v
        }
    }
}

/** Chaîne JSON ou null (évite les types plateforme de optString(key, null)). */
internal fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

/** GAID / IDFA « tout-à-zéro » (Limit Ad Tracking) → à ignorer. */
internal fun isZeroAdId(s: String): Boolean = s.isNotEmpty() && s.all { it == '0' || it == '-' }
