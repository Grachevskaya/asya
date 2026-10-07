package io.github.grachevskaya.asya.ui.adapter

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor
import io.github.grachevskaya.asya.ui.util.dpToPx
import io.github.grachevskaya.asya.ui.util.groupedRowBackground
import io.github.grachevskaya.asya.ui.view.ListItemView
import java.text.Collator
import java.util.Locale

class TemplateAdapter(
    private val onClickListener: ((ConfigManager.TemplateInfo) -> Unit)?,
) : RecyclerView.Adapter<TemplateAdapter.ViewHolder>() {
    private lateinit var list: List<ConfigManager.TemplateInfo>

    init {
        updateList()
    }

    inner class ViewHolder(
        view: ListItemView,
    ) : RecyclerView.ViewHolder(view) {
        init {
            view.setOnClickListener {
                onClickListener?.invoke(list[absoluteAdapterPosition])
            }
        }

        fun bind(info: ConfigManager.TemplateInfo) {
            with(itemView as ListItemView) {
                setIcon(
                    if (info.isWhiteList) {
                        R.drawable.outline_assignment_24
                    } else {
                        R.drawable.baseline_assignment_24
                    },
                )
                text = info.name
                binding.icon.imageTintList =
                    ColorStateList.valueOf(
                        context.themeColor(androidx.appcompat.R.attr.colorPrimary),
                    )

                background = context.groupedRowBackground(absoluteAdapterPosition, itemCount)
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = ListItemView(parent.context)
        val inset = 16.dpToPx.toInt()
        val gap = 3.dpToPx.toInt()
        view.layoutParams =
            RecyclerView
                .LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = inset
                    marginEnd = inset
                    topMargin = gap
                    bottomMargin = gap
                }
        return ViewHolder(view)
    }

    override fun getItemCount() = list.size

    override fun getItemId(position: Int) = list[position].name.hashCode().toLong()

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(list[position])

    @SuppressLint("NotifyDataSetChanged")
    fun updateList() {
        val allItems = ConfigManager.getTemplateList().toMutableList()

        list =
            allItems.apply {
                sortWith { o1, o2 ->
                    if (o1.type != o2.type) {
                        o1.type.compareTo(o2.type)
                    } else if (o1.isWhiteList != o2.isWhiteList) {
                        o1.isWhiteList.compareTo(o2.isWhiteList)
                    } else {
                        Collator.getInstance(Locale.getDefault()).compare(o1.name, o2.name)
                    }
                }
            }
        notifyDataSetChanged()
    }
}
