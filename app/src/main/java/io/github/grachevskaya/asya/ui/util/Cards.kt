package io.github.grachevskaya.asya.ui.util

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import io.github.grachevskaya.asya.ui.util.ThemeUtils.cornerSize

/*
 * The corners a row card wears inside a group, and the space that separates it from its
 * neighbours: the group's outer corners are large, the corners the rows share are small, and rows
 * are told apart by a hair of space rather than by a rule. The home screen and the about screen
 * both call this -- which is the whole point, the two pages cannot drift apart.
 */
private const val ROW_GAP_DP = 3

/**
 * Every card of this group, in the order it is laid out: the walk goes into the containers that
 * hold them -- a credited half is a list of its own, yet the group is all of them -- and leaves
 * out a folded list whole, because the corners belong to the rows one can see.
 */
fun ViewGroup.rowCards(): List<MaterialCardView> {
    val cards = mutableListOf<MaterialCardView>()

    fun collect(container: ViewGroup) {
        for (child in container.children) {
            // A folded list is out of the group whole: its rows keep their own visibility, so
            // the container is what decides whether they are part of the surface right now.
            if (!child.isVisible) continue
            when (child) {
                is MaterialCardView -> cards += child
                is ViewGroup -> collect(child)
            }
        }
    }

    collect(this)
    return cards
}

fun ViewGroup.styleSegmentedCards(context: Context) = rowCards().styleSegmentedCards(context)

fun List<MaterialCardView>.styleSegmentedCards(context: Context) {
    val largeRadius =
        context.cornerSize(
            com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge,
            com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraLarge,
            28,
        )
    val smallRadius =
        context.cornerSize(
            com.google.android.material.R.attr.shapeAppearanceCornerExtraSmall,
            com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraSmall,
            4,
        )
    val gap = (ROW_GAP_DP * context.resources.displayMetrics.density).toInt()

    forEachIndexed { index, card ->
        val first = index == 0
        val last = index == lastIndex
        card.shapeAppearanceModel =
            ShapeAppearanceModel
                .builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, if (first) largeRadius else smallRadius)
                .setTopRightCorner(CornerFamily.ROUNDED, if (first) largeRadius else smallRadius)
                .setBottomLeftCorner(CornerFamily.ROUNDED, if (last) largeRadius else smallRadius)
                .setBottomRightCorner(CornerFamily.ROUNDED, if (last) largeRadius else smallRadius)
                .build()

        (card.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            topMargin = if (first) 0 else gap
            bottomMargin = if (last) 0 else gap
        }
    }
}
