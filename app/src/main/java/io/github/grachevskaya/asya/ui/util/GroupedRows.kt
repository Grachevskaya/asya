package io.github.grachevskaya.asya.ui.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.github.grachevskaya.asya.ui.util.ThemeUtils.cornerSize
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor

/** Where a row sits in its group: the outer corners belong to the group, the inner ones to the gap. */
enum class RowGroup { Single, Top, Middle, Bottom }

/**
 * The background of a row in a group, drawn from the shape system the theme carries.
 *
 * The outer corners are the extra-large token and the ones that meet the next row are the
 * extra-small one, which is the shape a Material 3 expressive group of rows has; the ripple is masked
 * to the same shape so it stays inside them.
 */
fun Context.groupedRowBackground(group: RowGroup): Drawable {
    val outer = cornerSize(com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge, com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraLarge, 28)
    val inner = cornerSize(com.google.android.material.R.attr.shapeAppearanceCornerExtraSmall, com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraSmall, 4)
    val (top, bottom) = when (group) {
        RowGroup.Single -> outer to outer
        RowGroup.Top -> outer to inner
        RowGroup.Middle -> inner to inner
        RowGroup.Bottom -> inner to outer
    }

    fun model() =
        ShapeAppearanceModel
            .builder()
            .setTopLeftCorner(CornerFamily.ROUNDED, top)
            .setTopRightCorner(CornerFamily.ROUNDED, top)
            .setBottomLeftCorner(CornerFamily.ROUNDED, bottom)
            .setBottomRightCorner(CornerFamily.ROUNDED, bottom)
            .build()

    return RippleDrawable(
        ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight)),
        MaterialShapeDrawable(model()).apply {
            fillColor =
                ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorSurfaceBright),
                )
        },
        MaterialShapeDrawable(model()).apply { fillColor = ColorStateList.valueOf(Color.WHITE) },
    )
}

/** The same, for a row that knows its place by position. */
fun Context.groupedRowBackground(position: Int, count: Int): Drawable =
    groupedRowBackground(
        when {
            count <= 1 -> RowGroup.Single
            position <= 0 -> RowGroup.Top
            position >= count - 1 -> RowGroup.Bottom
            else -> RowGroup.Middle
        },
    )
