package io.github.grachevskaya.asya.ui.util

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.animation.doOnEnd
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * The folded half opens by growing: the view is measured at the height its contents ask for and is
 * then laid out from nothing up to it, with those contents clipped by it on the way. What moves is
 * the view making room for itself, not its contents fading in.
 *
 * This is the reveal the about page gives its folded list; a screen that pops a card open uses the
 * same one, so the two cannot drift apart.
 */
fun View.unfold(
    context: Context,
    opening: Boolean,
    width: Int,
    onClosed: () -> Unit = {},
) {
    if (opening) {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }

    val full = if (opening) measuredHeight else height

    if (!AccessibilityUtils.isAnimationEnabled(context.contentResolver)) {
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        if (!opening) visibility = View.GONE
        requestLayout()
        if (!opening) onClosed()
        return
    }

    val from = if (opening) 0 else full
    layoutParams.height = from
    requestLayout()

    ValueAnimator
        .ofInt(from, if (opening) full else 0)
        .apply {
            duration = 220L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                layoutParams.height = it.animatedValue as Int
                requestLayout()
            }
            doOnEnd {
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                if (!opening) visibility = View.GONE
                requestLayout()
                if (!opening) onClosed()
            }
            start()
        }
}

/**
 * A header that opens the half below it: the arrow turns over and the half grows out. Both the about
 * page's folded list and the restore card's answers open this way, so the three moves live here and
 * neither page writes them out again.
 */
class Reveal(
    private val chevron: View,
    private val folded: View,
    private val widthOf: () -> Int,
    private val onClosed: () -> Unit = {},
    initiallyOpen: Boolean = false,
) {
    var isOpen = initiallyOpen
        private set

    fun toggle() = setOpen(!isOpen)

    fun setOpen(open: Boolean) {
        isOpen = open
        chevron
            .animate()
            .rotation(if (open) 180f else 0f)
            .setDuration(STEP_MS)
            .start()
        if (open) folded.visibility = View.VISIBLE

        val width = widthOf().takeIf { it > 0 } ?: folded.resources.displayMetrics.widthPixels
        folded.unfold(folded.context, open, width, onClosed)
    }

    /** Puts the views back where [isOpen] says they are, without animating: a row is bound again. */
    fun restore() {
        chevron.rotation = if (isOpen) 180f else 0f
        folded.visibility = if (isOpen) View.VISIBLE else View.GONE
        folded.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private companion object {
        const val STEP_MS = 220L
    }
}
