package com.fastvpnn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCacheTest {
    private val now = 1_000_000_000_000L
    private fun snap(savedAt: Long) =
        ServerCache.Snapshot(listOf(Server(id = "a", endpointHost = "h", serverPublicKey = "k")), savedAt, "https://x")

    @Test fun freshJustAfterSaving() = assertTrue(snap(now).isFresh(now))

    @Test fun freshJustUnderOneDay() =
        assertTrue(snap(now - (ServerCache.TTL_MS - 1)).isFresh(now))

    @Test fun staleAtOneDay() = assertFalse(snap(now - ServerCache.TTL_MS).isFresh(now))

    @Test fun staleWhenClockWentBackwards() = assertFalse(snap(now + 60_000).isFresh(now))

    @Test fun ttlIsTwentyFourHours() = assertEquals(86_400_000L, ServerCache.TTL_MS)

    @Test fun flagEmojiForCountryCode() {
        assertEquals("🇩🇪", Server(countryCode = "de").flagEmoji())
        assertEquals("🌐", Server(countryCode = "").flagEmoji())
    }
}
