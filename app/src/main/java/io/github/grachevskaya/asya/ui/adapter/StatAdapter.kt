package io.github.grachevskaya.asya.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.CollectionUtils.sync
import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.databinding.StatItemViewBinding
import io.github.grachevskaya.asya.ui.util.groupedRowBackground
import io.github.grachevskaya.asya.util.PackageHelper

class StatAdapter(
    private val onBeginWaitForRefresh: (StatAdapter) -> Unit,
) : RecyclerView.Adapter<StatAdapter.ViewHolder>() {
    data class StatItem(
        val packageName: String,
        val filterCount: FilterHolder.FilterCount,
        val refreshing: Boolean,
    ) {
        val totalCount get() = filterCount.totalCount
    }

    private val logs = mutableListOf<StatItem>()

    var wasRefreshing = false

    internal fun addOrUpdateEntry(
        packageName: String,
        filterCount: FilterHolder.FilterCount,
    ) {
        val position = logs.indexOfFirst { it.packageName == packageName }
        val refreshing = PackageHelper.refreshing

        if (position < 0) {
            logs.add(StatItem(packageName, filterCount, refreshing))
            notifyItemInserted(logs.size - 1)
        } else {
            val item = logs[position]
            if (item.totalCount == filterCount.totalCount && !item.refreshing) return

            logs[position] = StatItem(packageName, filterCount, refreshing)

            val elements = logs.sortedWith { it1, it2 -> it1.totalCount.compareTo(it2.totalCount) }.asReversed()
            val newIndex = elements.indexOfFirst { it.packageName == packageName }

            logs.sync(elements)

            if (newIndex != position) {
                notifyItemMoved(position, newIndex)
                notifyItemChanged(newIndex)
            } else {
                notifyItemChanged(position)
            }
        }

        if (!wasRefreshing && refreshing) {
            wasRefreshing = true

            onBeginWaitForRefresh(this)
        }
    }

    internal fun clearEntriesIfNotFound(packageNames: Iterable<String>) {
        val keysToRemove = logs.filter { it.packageName !in packageNames }.map { it.packageName }

        for (key in keysToRemove) {
            val indexOfKey = logs.indexOfFirst { it.packageName == key }
            logs.removeAt(indexOfKey)
            notifyItemRemoved(indexOfKey)
        }
    }

    inner class ViewHolder(
        private val binding: StatItemViewBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(logItem: StatItem) {
            binding.root.background =
                binding.root.context.groupedRowBackground(absoluteAdapterPosition, itemCount)

            // The row remembers which app it is about: the icon arrives after the bind, and by then
            // the view may well be about another app.
            binding.root.tag = logItem.packageName
            binding.tag.text =
                if (logItem.refreshing) {
                    logItem.packageName
                } else {
                    PackageHelper.loadAppLabel(logItem.packageName)
                }
            binding.icon.setImageDrawable(PackageHelper.loadAppIcon(logItem.packageName))

            PackageHelper.loadAppIconAsync(logItem.packageName) { drawable ->
                if (binding.root.tag == logItem.packageName) {
                    binding.icon.setImageDrawable(drawable)
                }
            }

            binding.countPkgMgr.text = logItem.filterCount.packageManagerCount.toString()
            binding.countActLaunch.text = logItem.filterCount.activityLaunchCount.toString()
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val binding = StatItemViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = logs.size

    override fun getItemId(position: Int) = logs[position].hashCode().toLong()

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(logs[position])
}
