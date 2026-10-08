package io.github.grachevskaya.asya.ui.util

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel

/*
 * The corners a row card wears inside a group, and the space that separates it from its
 * neighbours: the group's outer corners are large, the corners the rows share are small, and rows
 * are told apart by a hair of space rather than by a rule. The home screen and the about screen
 * both call this -- which is the whole point, the two pages cannot drift apart.
 */

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
    val gap = context.groupedRowGap()

    forEachIndexed { index, card ->
        // Where this card sits decides its corners, by the same rule every other group of rows uses.
        val (top, bottom) = context.groupedRowRadii(groupedRowGroup(index, size), RowCorners.Cards)
        card.shapeAppearanceModel =
            ShapeAppearanceModel
                .builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, top)
                .setTopRightCorner(CornerFamily.ROUNDED, top)
                .setBottomLeftCorner(CornerFamily.ROUNDED, bottom)
                .setBottomRightCorner(CornerFamily.ROUNDED, bottom)
                .build()

        // The press is the one a settings row wears: a ripple masked to the shape this card has in
        // its group, drawn over the surface rather than replacing it.
        card.foreground =
            context.groupedRowRipple(groupedRowGroup(index, size), RowCorners.Cards)

        (card.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            topMargin = if (index == 0) 0 else gap
            bottomMargin = if (index == lastIndex) 0 else gap
        }
    }
}
