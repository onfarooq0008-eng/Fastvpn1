package com.fastvpnn.app.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fastvpnn.app.databinding.ItemAppBinding

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

class AppListAdapter(
    private val isBypassed: (String) -> Boolean,
    private val onToggle: (InstalledApp, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    private val items = mutableListOf<InstalledApp>()

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
        b.imageAppIcon.setImageDrawable(app.icon)
        b.checkboxBypass.setOnCheckedChangeListener(null)
        b.checkboxBypass.isChecked = isBypassed(app.packageName)
        b.checkboxBypass.setOnCheckedChangeListener { _, checked -> onToggle(app, checked) }
    }

    override fun getItemCount() = items.size
}
