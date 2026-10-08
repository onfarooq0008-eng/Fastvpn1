package com.fastvpnn.app.ui

import com.fastvpnn.app.data.Server

sealed class HomeRow {
    /** "Fastest Server" option at the top of the list: Connect picks a random server. */
    object FastestRow : HomeRow()
    data class Header(val group: CountryGroup, val expanded: Boolean) : HomeRow()
    data class ServerRow(val server: Server) : HomeRow()
    // slotId lets the adapter give each ad slot position a stable identity across
    // notifyDataSetChanged() calls so it doesn't reload a fresh native ad on every refresh.
    data class NativeAdRow(val slotId: Int) : HomeRow()
}
