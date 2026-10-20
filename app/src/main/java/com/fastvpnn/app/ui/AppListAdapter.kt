package com.fastvpnn.app.ui

import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fastvpnn.app.databinding.ItemAppBinding

data class InstalledApp(
    val packageName: String,
    val label: String
)

/**
 * Icons are loaded when a row is first shown (and kept in a small cache) instead of all at once up
 * front: a phone can have several hundred launchable apps, and holding every icon bitmap in memory
 * just to show the ~10 visible rows wasted tens of MB.
 */
class AppListAdapter(
    private val isBypassed: (String) -> Boolean,
    private val loadIcon: (String) -> Drawable?,
    private val onToggle: (InstalledApp, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    private val items = mutableListOf<InstalledApp>()
    private val iconCache = LruCache<String, Drawable>(64)

    fun submit(newItems: List<InstalledApp>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        val b = holder.binding
        b.textAppName.text = app.label
        b.imageAppIcon.setImageDrawable(
            iconCache.get(app.packageName) ?: loadIcon(app.packageName)?.also { iconCache.put(app.packageName, it) }
        )
        b.checkboxBypass.setOnCheckedChangeListener(null)
        b.checkboxBypass.isChecked = isBypassed(app.packageName)
        b.checkboxBypass.setOnCheckedChangeListener { _, checked -> onToggle(app, checked) }
    }

    override fun getItemCount() = items.size
}
