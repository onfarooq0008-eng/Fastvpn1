package com.fastvpnn.app.ui

import com.fastvpnn.app.data.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryGroupTest {

    private fun server(
        id: String,
        countryCode: String = "US",
        countryName: String = "United States",
        enabled: Boolean = true,
        pingMs: Int = -1
    ) = Server(id = id, countryCode = countryCode, countryName = countryName, enabled = enabled).apply {
        this.pingMs = pingMs
    }

    @Test
    fun `servers sharing a country code are grouped together`() {
        val servers = listOf(
            server("1", countryCode = "GB", countryName = "United Kingdom"),
            server("2", countryCode = "GB", countryName = "United Kingdom"),
            server("3", countryCode = "US", countryName = "United States")
        )
        val groups = CountryGroup.groupByCountry(servers)
        assertEquals(2, groups.size)
        val gb = groups.first { it.countryCode == "GB" }
        assertEquals(2, gb.servers.size)
    }

    @Test
    fun `disabled servers are excluded from grouping`() {
        val servers = listOf(
            server("1", enabled = true),
            server("2", enabled = false)
        )
        val groups = CountryGroup.groupByCountry(servers)
        assertEquals(1, groups.size)
        assertEquals(1, groups.first().servers.size)
    }

    @Test
    fun `groups are sorted alphabetically by country name`() {
        val servers = listOf(
            server("1", countryCode = "US", countryName = "United States"),
            server("2", countryCode = "DE", countryName = "Germany"),
            server("3", countryCode = "AU", countryName = "Australia")
        )
        val groups = CountryGroup.groupByCountry(servers)
        assertEquals(listOf("Australia", "Germany", "United States"), groups.map { it.countryName })
    }

    @Test
    fun `a typo in one server's country name doesn't win over the majority spelling`() {
        val servers = listOf(
            server("1", countryCode = "US", countryName = "United States"),
            server("2", countryCode = "US", countryName = "United States"),
            server("3", countryCode = "US", countryName = "Untied States") // typo
        )
        val groups = CountryGroup.groupByCountry(servers)
        assertEquals("United States", groups.first().countryName)
    }

    @Test
    fun `bestPingMs picks the lowest ping among reachable servers`() {
        val group = CountryGroup(
            "US", "United States",
            listOf(server("1", pingMs = 150), server("2", pingMs = 40), server("3", pingMs = -2))
        )
        assertEquals(40, group.bestPingMs())
    }

    @Test
    fun `bestPingMs is -1 (checking) if nothing has answered yet but something still might`() {
        val group = CountryGroup("US", "United States", listOf(server("1", pingMs = -1), server("2", pingMs = -2)))
        assertEquals(-1, group.bestPingMs())
    }

    @Test
    fun `bestPingMs is -2 (offline) only when every server is confirmed unreachable`() {
        val group = CountryGroup("US", "United States", listOf(server("1", pingMs = -2), server("2", pingMs = -2)))
        assertEquals(-2, group.bestPingMs())
    }

    @Test
    fun `flagEmoji renders a two-codepoint regional indicator flag for a valid country code`() {
        val group = CountryGroup("US", "United States", listOf(server("1", countryCode = "US")))
        val flag = group.flagEmoji()
        assertTrue("expected a non-empty flag emoji", flag.isNotEmpty())
        assertTrue("expected 2 codepoints for a flag emoji, got ${flag.codePointCount(0, flag.length)}",
            flag.codePointCount(0, flag.length) == 2)
    }
}
