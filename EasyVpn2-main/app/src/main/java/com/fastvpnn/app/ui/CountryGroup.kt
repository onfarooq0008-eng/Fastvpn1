package com.fastvpnn.app.ui

import com.fastvpnn.app.data.Server

/**
 * Not persisted -- computed on the fly from the flat server list so that when
 * you add a 2nd, 3rd, etc. server with the same country code on the backend,
 * they automatically appear grouped under one country entry here.
 * There's no cap on how many servers or countries you can add.
 */
data class CountryGroup(
    val countryCode: String,
    val countryName: String,
    val servers: List<Server>
) {
    fun flagEmoji(): String = servers.first().flagEmoji()

    /** Lowest ping among servers that have already answered; -1 if still pinging, -2 if all offline. */
    fun bestPingMs(): Int {
        val reachable = servers.filter { it.pingMs >= 0 }
        if (reachable.isNotEmpty()) return reachable.minOf { it.pingMs }
        return if (servers.any { it.pingMs == -1 }) -1 else -2
    }

    companion object {
        fun groupByCountry(servers: List<Server>): List<CountryGroup> {
            return servers
                .filter { it.enabled }
                .groupBy { it.countryCode }
                .map { (code, list) ->
                    // Use the most common country name spelling among servers sharing this code,
                    // in case of a typo in one entry.
                    val name = list.groupingBy { it.countryName }.eachCount().maxByOrNull { it.value }?.key ?: list.first().countryName
                    CountryGroup(code, name, list)
                }
                .sortedBy { it.countryName }
        }
    }
}
