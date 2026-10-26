package com.fastvpnn.app.ui

import com.fastvpnn.app.data.Server

sealed class HomeRow {
    /** "Fastest Server" option at the top of the list: Connect picks a random server. */
    object FastestRow : HomeRow()
    data class Header(val group: CountryGroup, val expanded: Boolean) : HomeRow()
    data class ServerRow(val server: Server) : HomeRow()
    /** The native ad card inside the Locations list (only present while an ad is loaded). */
    object AdRow : HomeRow()
}
