package com.fastvpnn.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.fastvpnn.app.R
import com.fastvpnn.app.ads.AdsManager
import com.fastvpnn.app.ads.NativeAdHandle
import com.fastvpnn.app.data.Server
import com.fastvpnn.app.databinding.ItemCountryBinding
import com.fastvpnn.app.databinding.ItemNativeAdBinding
import com.fastvpnn.app.databinding.ItemServerBinding

private const val VIEW_TYPE_HEADER = 0
private const val VIEW_TYPE_SERVER = 1
private const val VIEW_TYPE_NATIVE_AD = 2

class HomeListAdapter(
    private val onHeaderClick: (CountryGroup) -> Unit,
    private val onServerClick: (Server) -> Unit,
    private val onFavoriteClick: (CountryGroup) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<HomeRow>()
    private var connectedServerId: String? = null
    private var favorites: Set<String> = emptySet()

    // Cache loaded native ads per slot so scrolling/refreshing the list doesn't burn
    // a fresh ad request every time; cleared ads are destroyed to avoid leaking webviews.
    private val nativeAdCache = mutableMapOf<Int, NativeAdHandle>()
    private val loadingSlots = mutableSetOf<Int>()
    private val failedSlots = mutableSetOf<Int>()

    fun submit(newItems: List<HomeRow>, connectedServerId: String?, favorites: Set<String>) {
        val oldItems = items.toList()
        val connectedIdChanged = this.connectedServerId != connectedServerId
        this.connectedServerId = connectedServerId
        this.favorites = favorites
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                rowKey(oldItems[oldItemPosition]) == rowKey(newItems[newItemPosition])
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                // Ping times are mutated in place on shared Server objects, so data-class
                // equality can't see them change. Rebind server/country rows every time
                // (cheap; MainActivity turns off change animations). Ads stay untouched.
                return newItems[newItemPosition] is HomeRow.NativeAdRow &&
                    oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items.clear()
        items.addAll(newItems)
        // DiffUtil instead of notifyDataSetChanged(): now that the list also
        // auto-refreshes every 20s (see MainActivity), a full reset would rebind
        // every visible row -- including flashing native ads -- on every tick. This
        // only touches rows that actually changed, so everything else (and scroll
        // position) holds still.
        diff.dispatchUpdatesTo(this)
    }

    private fun rowKey(row: HomeRow): Any = when (row) {
        is HomeRow.Header -> "header_${row.group.countryCode}"
        is HomeRow.ServerRow -> "server_${row.server.id}"
        is HomeRow.NativeAdRow -> "ad_${row.slotId}"
    }

    /** Call from the host Activity's onDestroy to release native ad resources. */
    fun destroyAds() {
        nativeAdCache.values.forEach { it.destroy() }
        nativeAdCache.clear()
        loadingSlots.clear()
        failedSlots.clear()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeRow.Header -> VIEW_TYPE_HEADER
        is HomeRow.ServerRow -> VIEW_TYPE_SERVER
        is HomeRow.NativeAdRow -> VIEW_TYPE_NATIVE_AD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            VIEW_TYPE_NATIVE_AD -> NativeAdVH(ItemNativeAdBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> ServerVH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is HomeRow.Header -> (holder as HeaderVH).bind(row)
            is HomeRow.ServerRow -> (holder as ServerVH).bind(row.server)
            is HomeRow.NativeAdRow -> (holder as NativeAdVH).bind(row.slotId)
        }
    }

    override fun getItemCount() = items.size

    private fun connectedStyle(card: com.google.android.material.card.MaterialCardView, connected: Boolean, normalBg: Int) {
        val ctx = card.context
        card.setCardBackgroundColor(ContextCompat.getColor(ctx, if (connected) R.color.connectedTint else normalBg))
        card.strokeColor = ContextCompat.getColor(ctx, if (connected) R.color.statusOnline else R.color.divider)
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
            b.buttonFavorite.setOnClickListener { onFavoriteClick(group) }
            b.root.setOnClickListener { onHeaderClick(group) }
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
            b.root.setOnClickListener { onServerClick(server) }
        }
    }

    inner class NativeAdVH(val b: ItemNativeAdBinding) : RecyclerView.ViewHolder(b.root) {

        /** The row stays collapsed (0 height) until an ad is actually loaded -- no blank cards. */
        private fun setRowVisible(visible: Boolean) {
            val lp = itemView.layoutParams
            lp.height = if (visible) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            itemView.layoutParams = lp
            itemView.visibility = if (visible) View.VISIBLE else View.GONE
        }

        fun bind(slotId: Int) {
            val cached = nativeAdCache[slotId]
            if (cached != null && cached.isValid) {
                setRowVisible(true)
                cached.render(b)
                return
            }
            nativeAdCache.remove(slotId)?.destroy() // stale/invalidated -> request a fresh one
            setRowVisible(false)
            if (slotId in loadingSlots || slotId in failedSlots) return
            loadingSlots.add(slotId)
            AdsManager.loadNativeAd(
                b.root.context,
                onLoaded = { ad ->
                    loadingSlots.remove(slotId)
                    nativeAdCache[slotId] = ad
                    // The row may have been recycled to another slot while loading.
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                        (items.getOrNull(bindingAdapterPosition) as? HomeRow.NativeAdRow)?.slotId == slotId
                    ) {
                        setRowVisible(true)
                        ad.render(b)
                    } else {
                        notifyDataSetChanged() // let whichever holder now shows this slot pick it up
                    }
                },
                onFailed = {
                    loadingSlots.remove(slotId)
                    failedSlots.add(slotId) // don't hammer the network retrying a failing placement
                }
            )
        }
    }
}
