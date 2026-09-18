package io.getstamina.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventsTest {
    @Test fun normalizeName() {
        assertEquals("level_complete", Events.normalizeName("  Level-Complete "))
        assertTrue("level_complete" in Events.standard)
    }

    @Test fun normalizeStep() {
        assertEquals("choose_goal", Events.normalizeStep("Choose Goal!"))
        assertEquals("allow_notifications", Events.normalizeStep("  allow - notifications  "))
        assertEquals("", Events.normalizeStep("   "))
        assertEquals(40, Events.normalizeStep("a".repeat(60)).length)
    }

    @Test fun revenue() {
        assertEquals(9.99, Events.coerceRevenue(mapOf("revenue" to 9.99))!!, 0.0)
        assertEquals(4.99, Events.coerceRevenue(mapOf("price" to "4.99"))!!, 0.0)
        assertEquals(3.0, Events.coerceRevenue(mapOf("price" to 3))!!, 0.0)
        assertNull(Events.coerceRevenue(mapOf("price" to "abc")))
        assertNull(Events.coerceRevenue(mapOf("revenue" to true)))
        assertNull(Events.coerceRevenue(null))
    }

    @Test fun makeEvent() {
        val now = Date(1_789_725_858_673L)
        val p = Events.make("purchase", null, mapOf("revenue" to 9.99, "currency" to "eur", "product" to "pro"), now)
        assertEquals("purchase", p.getString("name"))
        assertTrue(p.isNull("subtype"))
        assertEquals(9.99, p.getDouble("revenue"), 0.0)
        assertEquals("EUR", p.getString("currency"))
        assertEquals("pro", p.getJSONObject("params").getString("product"))
        assertFalse(p.getJSONObject("params").has("revenue"))
        assertEquals("2026-09-18T10:04:18.673Z", p.getString("ts"))

        val c = Events.make("Boss-Defeated", null, null, now)
        assertEquals("custom", c.getString("name"))
        assertEquals("boss_defeated", c.getString("subtype"))
        assertFalse(c.has("params"))

        val o = Events.make("onboarding", "Choose Goal!", mapOf("step" to 2), now)
        assertEquals("choose_goal", o.getString("subtype"))
        JSONObject(o.toString()) // sérialisable
    }

    @Test fun zeroAdId() {
        assertTrue(isZeroAdId("00000000-0000-0000-0000-000000000000"))
        assertFalse(isZeroAdId("AAAAAAAA-1111-2222-3333-444444444444"))
        assertFalse(isZeroAdId(""))
    }
}
