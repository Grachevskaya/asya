package io.github.grachevskaya.asya.ui.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.grachevskaya.asya.databinding.LogItemViewBinding
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor
import java.util.regex.Pattern

class LogAdapter(
    context: Context,
) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
    class LogItem(
        val level: String,
        val date: String,
        val tag: String,
        val message: String,
    )

    companion object {
        private val debugLevels = arrayOf("DEBUG", "VERBS")

        private val pattern = Pattern.compile("\\[ ?(.*)] <(.*)> \\((.*?)\\) (.*)", Pattern.DOTALL)

        /*
         * The colour a line's letter sits in. It marks attention, not decoration: the ordinary lines
         * are the quiet surface, and only a warning or an error is a colour of its own.
         */
        private val SURFACE_TONE = com.google.android.material.R.attr.colorSurfaceContainerHighest
        private val PLAIN_INK = com.google.android.material.R.attr.colorOnSurface
        private val QUIET_INK = com.google.android.material.R.attr.colorOnSurfaceVariant

        private val TONES: Map<String, Pair<Int, Int>> =
            mapOf(
                "VERBS" to Pair(SURFACE_TONE, QUIET_INK),
                "DEBUG" to Pair(SURFACE_TONE, QUIET_INK),
                "INFO" to Pair(SURFACE_TONE, PLAIN_INK),
                "WARN" to
                    Pair(
                        com.google.android.material.R.attr.colorTertiary,
                        com.google.android.material.R.attr.colorOnTertiary,
                    ),
                "ERROR" to
                    Pair(
                        android.R.attr.colorError,
                        com.google.android.material.R.attr.colorOnError,
                    ),
            )

        fun parseLog(text: String): LogItem? {
            val matcher = pattern.matcher(text)
            if (matcher.matches()) {
                val level = matcher.group(1) ?: return null
                if (level in debugLevels &&
                    PrefManager.logFilter_level > 0 ||
                    level == "INFO" &&
                    PrefManager.logFilter_level > 1 ||
                    level == "WARN" &&
                    PrefManager.logFilter_level > 2
                ) {
                    return null
                }
                val date = matcher.group(2) ?: return null
                val tag = matcher.group(3) ?: return null
                val message = matcher.group(4) ?: return null
                return LogItem(level, date, tag, message)
            }

            return null
        }
    }

    private val context = context.applicationContext

    var logs = listOf<LogItem>()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    inner class ViewHolder(
        private val binding: LogItemViewBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(logItem: LogItem) {
            val tone =
                TONES[logItem.level]
                    ?: throw IllegalArgumentException("Unknown level: ${logItem.level}")

            /*
             * The theme is read from the row's own context and not from the application's: the manager
             * runs inside the host, and the two do not carry the same theme -- a colour the host's
             * theme does not define comes back as the fallback, which is how every badge turned one
             * colour with nothing readable in it.
             */
            val theme = binding.level.context
            val level = binding.level
            level.text = logItem.level.take(1)
            level.backgroundTintList = ColorStateList.valueOf(theme.themeColor(tone.first))
            level.setTextColor(theme.themeColor(tone.second))

            binding.date.text = logItem.date
            binding.tag.text = logItem.tag
            binding.message.text = logItem.message
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val binding = LogItemViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = logs.size

    override fun getItemId(position: Int) = logs[position].hashCode().toLong()

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(logs[position])
}
