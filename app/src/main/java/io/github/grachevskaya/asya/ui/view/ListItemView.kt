package io.github.grachevskaya.asya.ui.view

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import dev.androidbroadcast.vbpd.CreateMethod
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor
import io.github.grachevskaya.asya.ui.util.dpToPx
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.ListItemViewBinding

class ListItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {

    val binding by viewBinding<ListItemViewBinding>(createMethod = CreateMethod.INFLATE)

    init {
        val typedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.ListItemView, defStyleAttr, defStyleRes)
        val icon = typedArray.getResourceId(R.styleable.ListItemView_icon, 0)
        val text = typedArray.getString(R.styleable.ListItemView_text)
        val subText = typedArray.getString(R.styleable.ListItemView_subText)
        val buttonText = typedArray.getText(R.styleable.ListItemView_buttonText)
        typedArray.recycle()
        binding.icon.setImageResource(icon)
        binding.text.text = text

        binding.subText.visibility = if (subText != null) VISIBLE else GONE
        binding.subText.text = subText

        if (buttonText != null) {
            binding.button.visibility = VISIBLE
            binding.button.text = buttonText
        }
    }

    var text: CharSequence?
        get() = binding.text.text
        set(value) {
            binding.text.text = value
        }

    var subText: CharSequence?
        get() = binding.subText.text
        set(value) {
            binding.subText.visibility = if (value != null) VISIBLE else GONE
            binding.subText.text = value
        }

    fun setIcon(@DrawableRes icon: Int) {
        binding.icon.setImageResource(icon)
    }

    fun showIcon(show: Boolean) {
        binding.icon.isVisible = show
    }

    override fun setOnClickListener(l: OnClickListener?) {
        if (binding.button.isVisible) {
            binding.button.setOnClickListener(l)
        } else {
            super.setOnClickListener(l)
        }
    }
}
