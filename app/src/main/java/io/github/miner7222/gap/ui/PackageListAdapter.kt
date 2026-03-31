package io.github.miner7222.gap.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.miner7222.gap.R
import io.github.miner7222.gap.databinding.ItemPackageEntryBinding

class PackageListAdapter(
    private val packageManager: PackageManager,
    private val onToggle: (PackageEntry) -> Unit,
) : RecyclerView.Adapter<PackageListAdapter.PackageViewHolder>() {

    private val items = mutableListOf<PackageEntry>()

    fun submitList(entries: List<PackageEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPackageEntryBinding.inflate(inflater, parent, false)
        return PackageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PackageViewHolder(
        private val binding: ItemPackageEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: PackageEntry) {
            binding.appName.text = entry.label
            binding.packageName.text = if (entry.installed) {
                entry.packageName
            } else {
                binding.root.context.getString(R.string.package_name_not_installed, entry.packageName)
            }
            binding.checkbox.isChecked = entry.selected
            binding.icon.setImageDrawable(resolveIcon(entry))

            val toggle = {
                entry.selected = !entry.selected
                binding.checkbox.isChecked = entry.selected
                onToggle(entry)
            }

            binding.root.setOnClickListener { toggle() }
            binding.checkbox.setOnClickListener { toggle() }
        }

        private fun resolveIcon(entry: PackageEntry): Drawable? {
            return entry.applicationInfo?.loadIcon(packageManager)
                ?: ContextCompat.getDrawable(binding.root.context, android.R.drawable.sym_def_app_icon)
        }
    }
}
