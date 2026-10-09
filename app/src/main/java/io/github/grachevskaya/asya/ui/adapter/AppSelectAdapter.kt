package io.github.grachevskaya.asya.ui.adapter

import android.widget.Filter
import android.widget.Filterable
import androidx.recyclerview.widget.RecyclerView
import io.github.grachevskaya.asya.common.CollectionUtils.sync
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.ui.util.get
import io.github.grachevskaya.asya.ui.view.AppItemView
import io.github.grachevskaya.asya.util.PackageHelper
import kotlinx.coroutines.runBlocking

abstract class AppSelectAdapter(
    private val firstFilter: ((String) -> Boolean)? = null,
) : RecyclerView.Adapter<AppSelectAdapter.ViewHolder>(),
    Filterable {
    abstract class ViewHolder(
        view: AppItemView,
    ) : RecyclerView.ViewHolder(view) {
        abstract fun bind(packageName: String)
    }

    private inner class AppFilter : Filter() {
        override fun performFiltering(constraint: CharSequence): FilterResults {
            return runBlocking {
                val constraintLowered = constraint.toString().trim().lowercase()
                val filteredList =
                    PackageHelper.appList.get().filter {
                        if (firstFilter?.invoke(it) == false) return@filter false
                        if (!PrefManager.appFilter_showSystem && PackageHelper.isSystem(it)) return@filter false
                        val label = PackageHelper.loadAppLabel(it)
                        label.lowercase().contains(constraintLowered) || it.lowercase().contains(constraintLowered)
                    }

                FilterResults().also { it.values = filteredList }
            }
        }

        @Suppress("UNCHECKED_CAST", "NotifyDataSetChanged")
        override fun publishResults(
            constraint: CharSequence,
            results: FilterResults,
        ) {
            val elements = results.values as? List<String> ?: return
            filteredList.sync(elements)
            notifyDataSetChanged()
        }
    }

    private val mFilter = AppFilter()

    protected val filteredList: MutableList<String> = mutableListOf()

    override fun getItemCount() = filteredList.size

    override fun getItemId(position: Int) = filteredList[position].hashCode().toLong()

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(filteredList[position])

    override fun getFilter(): Filter = mFilter
}
