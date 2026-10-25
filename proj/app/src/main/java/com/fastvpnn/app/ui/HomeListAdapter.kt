package com.fastvpnn.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.fastvpnn.app.R
import com.fastvpnn.app.data.Server
import com.fastvpnn.app.databinding.ItemCountryBinding
import com.fastvpnn.app.databinding.ItemFastestBinding
import com.fastvpnn.app.databinding.ItemServerBinding

private const val VIEW_TYPE_HEADER = 0
private const val VIEW_TYPE_SERVER = 1
private const val VIEW_TYPE_FASTEST = 3

class HomeListAdapter(
    private val onHeaderClick: (CountryGroup) -> Unit,
    private val onServerClick: (Server) -> Unit,
    private val onFavoriteClick: (CountryGroup) -> Unit,
    private val onFastestClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<HomeRow>()
    private var connectedServerId: String? = null
    private var favorites: Set<String> = emptySet()
    private var fastestSelected = false

    // What each row looked like when it was last shown. Ping times are mutated in place on shared
    // Server objects, so comparing the row objects themselves can never see them change -- a snapshot
    // string per row can. DiffUtil then only rebinds rows whose snapshot differs (and scroll position
    // and ripple state of everything else are left alone on every 30 s refresh).
    private var signatures: List<String> = emptyList()

    fun submit(newItems: List<HomeRow>, connectedServerId: String?, favorites: Set<String>, fastestSelected: Boolean = false) {
        val oldItems = items.toList()
        val oldSignatures = signatures
        this.connectedServerId = connectedServerId
        this.favorites = favorites
        this.fastestSelected = fastestSelected
        val newSignatures = newItems.map { signature(it) }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                rowKey(oldItems[oldItemPosition]) == rowKey(newItems[newItemPosition])
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldSignatures[oldItemPosition] == newSignatures[newItemPosition]
        })
        items.clear()
        items.addAll(newItems)
        signatures = newSignatures
        diff.dispatchUpdatesTo(this)
    }

    private fun signature(row: HomeRow): String = when (row) {
        is HomeRow.Header -> {
            val g = row.group
            "H|${g.countryCode}|${g.countryName}|${g.servers.size}|${g.servers.firstOrNull()?.city}|${row.expanded}|" +
                "${g.bestPingMs()}|${connectedServerId != null && g.servers.any { it.id == connectedServerId }}|" +
                favorites.contains(g.countryCode)
        }
        is HomeRow.ServerRow -> {
            val s = row.server
            "S|${s.id}|${s.name}|${s.countryName}|${s.city}|${s.pingMs}|${connectedServerId == s.id}"
        }
        is HomeRow.FastestRow -> "F|$fastestSelected"
    }

    private fun rowKey(row: HomeRow): Any = when (row) {
        is HomeRow.Header -> "header_${row.group.countryCode}"
        is HomeRow.ServerRow -> "server_${row.server.id}"
        is HomeRow.FastestRow -> "fastest"
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeRow.Header -> VIEW_TYPE_HEADER
        is HomeRow.ServerRow -> VIEW_TYPE_SERVER
        is HomeRow.FastestRow -> VIEW_TYPE_FASTEST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            VIEW_TYPE_FASTEST -> FastestVH(ItemFastestBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> ServerVH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is HomeRow.Header -> (holder as HeaderVH).bind(row)
            is HomeRow.ServerRow -> (holder as ServerVH).bind(row.server)
            is HomeRow.FastestRow -> (holder as FastestVH).bind()
        }
    }

    override fun getItemCount() = items.size

    private inline fun <reified T : HomeRow> currentRow(holder: RecyclerView.ViewHolder): T? =
        items.getOrNull(holder.bindingAdapterPosition) as? T

    private fun connectedStyle(card: com.google.android.material.card.MaterialCardView, connected: Boolean, normalBg: Int) {
        val ctx = card.context
        card.setCardBackgroundColor(ContextCompat.getColor(ctx, if (connected) R.color.connectedTint else normalBg))
        card.strokeColor = ContextCompat.getColor(ctx, if (connected) R.color.statusOnline else R.color.divider)
    }

    inner class FastestVH(val b: ItemFastestBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            val ctx = b.root.context
            val selected = fastestSelected
            b.root.strokeColor = ContextCompat.getColor(ctx, if (selected) R.color.primary else R.color.divider)
            b.imageSelected.setImageResource(if (selected) R.drawable.ic_check_circle else R.drawable.ic_radio_off)
            b.imageSelected.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (selected) R.color.primary else R.color.gray)
            )
        }

        init {
            b.root.setOnClickListener { onFastestClick() }
        }
    }

    inner class HeaderVH(val b: ItemCountryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: HomeRow.Header) {
            val ctx = b.root.context
            val group = row.group
            val connected = connectedServerId != null && group.servers.any { it.id == connectedServerId }
            val bestPing = group.bestPingMs()

            b.textFlag.text = group.flagEmoji()
            b.textCountryName.text = group.countryName

            val base = if (group.servers.size == 1) {
                group.servers.first().city.ifBlank { group.countryName }
            } else {
                "${group.servers.size} servers " + if (row.expanded) "▴" else "▾"
            }
            if (bestPing == -2) {
                b.textServerCount.text = "$base • Offline"
                b.textServerCount.setTextColor(ContextCompat.getColor(ctx, R.color.statusOffline))
            } else {
                b.textServerCount.text = base
                b.textServerCount.setTextColor(ContextCompat.getColor(ctx, R.color.gray))
            }

            b.signal.level = SignalBarsView.levelForPing(bestPing)
            b.signal.contentDescription = if (bestPing >= 0) "$bestPing ms" else "No signal"
            b.textConnected.visibility = if (connected) View.VISIBLE else View.GONE
            connectedStyle(b.root, connected, R.color.cardBackground)

            val fav = favorites.contains(group.countryCode)
            b.buttonFavorite.setImageResource(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star_border)
            b.buttonFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (fav) R.color.gold else R.color.gray)
            )
            b.buttonFavorite.contentDescription = if (fav) "Remove from favorites" else "Add to favorites"
        }

        init {
            b.buttonFavorite.setOnClickListener { currentRow<HomeRow.Header>(this)?.let { onFavoriteClick(it.group) } }
            b.root.setOnClickListener { currentRow<HomeRow.Header>(this)?.let { onHeaderClick(it.group) } }
        }
    }

    inner class ServerVH(val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(server: Server) {
            val connected = connectedServerId == server.id
            b.textFlag.text = server.flagEmoji()
            b.textName.text = server.name.ifBlank { server.countryName }
            val where = if (server.city.isNotBlank()) "${server.countryName} • ${server.city}" else server.countryName
            if (server.pingMs == -2) {
                b.textCity.text = "$where • Offline"
                b.textCity.setTextColor(ContextCompat.getColor(b.root.context, R.color.statusOffline))
            } else {
                b.textCity.text = if (server.pingMs >= 0) "$where • ${server.pingMs} ms" else where
                b.textCity.setTextColor(ContextCompat.getColor(b.root.context, R.color.gray))
            }
            b.signal.level = SignalBarsView.levelForPing(server.pingMs)
            b.textConnected.visibility = if (connected) View.VISIBLE else View.GONE
            connectedStyle(b.root, connected, R.color.rowBackground)
        }

        init {
            b.root.setOnClickListener { currentRow<HomeRow.ServerRow>(this)?.let { onServerClick(it.server) } }
        }
    }

}
