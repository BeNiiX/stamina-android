// Scénarios de conformité S1…S9 (stamina-sdk-conformance/scenarios.md) — mêmes vérifications que les
// clients React Native et iOS, sous Robolectric contre le serveur Node (scripts/test.sh le lance).
package io.getstamina.sdk

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConformanceTest {
    private val server = ConformanceServer
    private val storage = InMemoryStorage()
    private val signals = FakeSignals()
    private val apiKey = "stam_android_test_conformance"
    private lateinit var app: Application
    private lateinit var client: StaminaClient

    @Before
    fun setUp() {
        assumeTrue("serveur de conformité injoignable — lance `node stamina-sdk-conformance/server.js`", server.reachable())
        app = RuntimeEnvironment.getApplication()
        Stamina._resetForTests()
        client = Stamina.clientFor(app)
        storage.clear()
        signals.att = TrackingStatus.AUTHORIZED
        signals.adId = "AAAAAAAA-1111-2222-3333-444444444444"
        runBlocking { client.onThread { _setStorage(storage); _setSignals(signals); _setNow { Date() } } }
        server.reset()
    }

    @After
    fun tearDown() { Stamina._resetForTests() }

    // ── outils ─────────────────────────────────────────────────────────────

    private var t0 = Date()
    private fun configure() { t0 = Date(); Stamina.configure(app, StaminaConfig(apiKey = apiKey, endpoint = server.base)) }
    private fun sleep(ms: Long) = Thread.sleep(ms)
    /** Horloge du SDK = instant du configure() + `seconds` (indépendant de la durée réelle des tests). */
    private fun timeTravel(seconds: Long) { val at = Date(t0.time + seconds * 1000); runBlocking { client.onThread { _setNow { at } } } }
    private fun params(e: JSONObject): JSONObject = e.getJSONObject("params")

    /** Vrai cycle de vie : Robolectric dispatche start/stop aux ActivityLifecycleCallbacks enregistrés. */
    private fun activity(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create()

    // ── scénarios ──────────────────────────────────────────────────────────

    @Test fun s1_install() {
        configure(); sleep(600)
        val installs = server.calls("/v1/sdk/install")
        assertEquals("un install", 1, installs.size)
        val b = server.body(installs[0])
        val headers = installs[0].getJSONObject("headers")
        assertEquals("Bearer $apiKey", headers.getString("authorization"))
        assertTrue("User-Agent", headers.getString("user-agent").startsWith("Stamina-Android/${BuildConfig.SDK_VERSION}"))
        for (f in listOf("device_user_id", "os_version", "device_model", "device_manufacturer", "locale", "timezone", "webview_user_agent", "screen_w", "screen_h")) {
            assertTrue("champ $f", b.has(f) && !b.isNull(f))
        }
        val extras = b.getJSONObject("extras")
        assertEquals("android", extras.getString("sdk_platform"))
        assertEquals(BuildConfig.SDK_VERSION, extras.getString("sdk_version"))
        assertEquals("1.0.0", extras.getString("app_version"))
        assertEquals("production", extras.getString("environment"))
        assertEquals(signals.adId, b.getString("gaid"))
        assertEquals("utm_source=stamina&slug=conf&click_id=c1", b.getString("install_referrer"))
        assertTrue(b.isNull("adservices_token"))
        assertTrue(b.isNull("idfa"))
    }

    @Test fun s2_events() {
        configure(); sleep(300)
        Stamina.sendEvent("purchase", params = mapOf("revenue" to 9.99, "currency" to "eur", "product" to "pro"))
        Stamina.sendEvent("Level-Complete", params = mapOf("level" to 5))
        Stamina.sendEvent("Boss-Defeated", params = mapOf("boss" to "x"))
        Stamina.sendEvent("subscribe", params = mapOf("price" to "4.99", "currency" to "USD"))
        Stamina.trackOnboardingStep("Choose Goal!", step = 2, params = mapOf("extra" to 1))
        Stamina.trackOnboardingStep("   ")
        sleep(800)
        val evs = server.events().filter { it.getString("name") != "session_start" }
        assertEquals("5 événements", 5, evs.size)
        val (p, lc, bd, sub, ob) = evs
        assertEquals("purchase", p.getString("name")); assertTrue(p.isNull("subtype"))
        assertEquals(9.99, p.getDouble("revenue"), 0.0); assertEquals("EUR", p.getString("currency"))
        assertEquals("pro", params(p).getString("product")); assertFalse(params(p).has("revenue"))
        assertEquals("level_complete", lc.getString("name")); assertTrue(lc.isNull("subtype"))
        assertEquals("custom", bd.getString("name")); assertEquals("boss_defeated", bd.getString("subtype"))
        assertEquals("subscribe", sub.getString("name")); assertEquals(4.99, sub.getDouble("revenue"), 0.0); assertEquals("USD", sub.getString("currency"))
        assertEquals("onboarding", ob.getString("name")); assertEquals("choose_goal", ob.getString("subtype"))
        assertEquals(2, params(ob).getInt("step")); assertEquals(1, params(ob).getInt("extra"))
        for (e in evs) { assertTrue("id local", e.getString("id").length > 10); assertTrue("ts ISO", e.getString("ts").endsWith("Z")) }
    }

    @Test fun s3_sessions() {
        configure(); sleep(400)
        var starts = server.events().filter { it.getString("name") == "session_start" }
        assertEquals("session_start n°1", 1, starts.size)
        assertEquals(1, params(starts[0]).getInt("session_number"))
        val sid = params(starts[0]).getString("session_id")

        val controller = activity()
        controller.start(); sleep(200) // premier plan : même session (< 30 min)
        timeTravel(65)
        controller.stop(); sleep(400)  // arrière-plan → session_end
        val end = server.events().first { it.getString("name") == "session_end" }
        assertEquals(sid, params(end).getString("session_id"))
        assertEquals("session_end 65 s", 65, params(end).getInt("duration_s"))

        timeTravel(10 * 60)
        controller.restart(); sleep(400)
        assertEquals("pas de nouvelle session < 30 min", 1, server.events().count { it.getString("name") == "session_start" })

        timeTravel(41 * 60); controller.stop(); sleep(300)
        timeTravel(72 * 60); controller.restart(); sleep(400)
        starts = server.events().filter { it.getString("name") == "session_start" }
        assertEquals("session_start n°2 après 30 min", 2, starts.size)
        assertEquals(2, params(starts[1]).getInt("session_number"))
        assertNotEquals(sid, params(starts[1]).getString("session_id"))
    }

    @Test fun s4_killSwitch() {
        configure(); sleep(300)
        server.mode("auth_reject_json")
        Stamina.sendEvent("login"); sleep(500)
        assertTrue("désactivé", Stamina.isDisabled)
        assertEquals("persisté", "1", storage.get(Keys.DISABLED))
        val before = server.calls().size
        Stamina.sendEvent("login"); runBlocking { Stamina.flush() }; sleep(300)
        assertEquals("plus aucun appel", before, server.calls().size)
        server.mode("ok")
        runBlocking { Stamina.clearData() }
        assertFalse("réactivé", Stamina.isDisabled)
    }

    @Test fun s5_forbiddenHtml() {
        configure(); sleep(300)
        server.mode("forbidden_html")
        Stamina.sendEvent("login"); sleep(500)
        assertFalse("pas désactivé", Stamina.isDisabled)
        val n = server.calls("/v1/sdk/events").size
        Stamina.sendEvent("login"); sleep(400)
        assertEquals("backoff : pas de nouvel envoi immédiat", n, server.calls("/v1/sdk/events").size)
        server.mode("ok")
        runBlocking { Stamina.flush() }; sleep(300)
        val last = server.calls("/v1/sdk/events").last()
        val names = server.body(last).getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertTrue("file conservée puis envoyée", "login" in names)
    }

    /** Variante Android de S6 : GAID indisponible à l'install (Play Services absent sur le moment),
     *  disponible ensuite → un seul identify, puis rien tant qu'il ne change pas. */
    @Test fun s6_gaidLater() {
        signals.adId = null
        configure(); sleep(600)
        assertTrue(server.body(server.calls("/v1/sdk/install")[0]).isNull("gaid"))
        assertEquals("rien sans GAID", 0, server.calls("/v1/sdk/identify").size)

        signals.adId = "AAAAAAAA-1111-2222-3333-444444444444"
        val controller = activity()
        controller.start(); sleep(500)
        var ids = server.calls("/v1/sdk/identify")
        assertEquals("un identify", 1, ids.size)
        assertEquals(signals.adId, server.body(ids[0]).getString("advertising_id"))

        controller.stop(); controller.restart(); sleep(300)
        assertEquals("pas de renvoi", 1, server.calls("/v1/sdk/identify").size)

        signals.adId = "BBBBBBBB-1111-2222-3333-444444444444"
        controller.stop(); controller.restart(); sleep(500)
        ids = server.calls("/v1/sdk/identify")
        assertEquals("renvoi si l'identifiant change", 2, ids.size)
    }

    @Test fun s7_installOnce() {
        configure(); sleep(500)
        runBlocking { Stamina.destroy() }
        configure(); sleep(500)
        assertEquals("un seul install", 1, server.calls("/v1/sdk/install").size)
    }

    @Test fun s8_corruptedQueue() {
        storage.set(Keys.QUEUE, "not-json{")
        configure(); sleep(300)
        Stamina.sendEvent("login"); sleep(500)
        assertTrue("événement envoyé après réinitialisation", server.events().any { it.getString("name") == "login" })
    }

    @Test fun s9_batches() {
        configure(); sleep(400)
        server.reset(); server.mode("offline")
        repeat(250) { i -> Stamina.sendEvent("view_content", params = mapOf("i" to i)) }
        sleep(800)
        server.mode("ok")
        runBlocking { Stamina.flush() }; sleep(300)
        val sizes = server.calls("/v1/sdk/events").map { server.body(it).getJSONArray("events").length() }
        assertEquals("lots $sizes", listOf(100, 100, 50), sizes)
        assertNotNull(sizes)
    }
}
