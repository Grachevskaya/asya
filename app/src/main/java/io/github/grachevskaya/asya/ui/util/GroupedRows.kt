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

/** The space between two rows of a group, in dp, before the screen turns it into pixels. */
internal const val ROW_GAP_DP = 3

/** One of the shape system's corner tokens, read through the theme that carries it. */
class Corner(
    val attr: Int,
    val style: Int,
    val fallback: Int,
)

/**
 * The pair of corners a group of rows rounds with. Every surface in the manager asks for its corners
 * here, so a page cannot invent a rounding of its own: the settings lists wear the tighter pair and
 * the hand built card pages the expressive one, and the choice is written down exactly once.
 */
enum class RowCorners(
    val outer: Corner,
    val inner: Corner,
) {
    List(
        Corner(
            com.google.android.material.R.attr.shapeAppearanceCornerLarge,
            com.google.android.material.R.style.ShapeAppearance_Material3_Corner_Large,
            16,
        ),
        Corner(
            com.google.android.material.R.attr.shapeAppearanceCornerSmall,
            com.google.android.material.R.style.ShapeAppearance_Material3_Corner_Small,
            8,
        ),
    ),
    Cards(
        Corner(
            com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge,
            com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraLarge,
            28,
        ),
        Corner(
            com.google.android.material.R.attr.shapeAppearanceCornerExtraSmall,
            com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraSmall,
            4,
        ),
    ),
}

/** The hair of space that tells the rows of one group apart. */
fun Context.groupedRowGap(): Int = (ROW_GAP_DP * resources.displayMetrics.density).toInt()

/** Where a row of [count] rows at [position] sits: the same rule for every group of rows there is. */
fun groupedRowGroup(
    position: Int,
    count: Int,
): RowGroup =
    when {
        count <= 1 -> RowGroup.Single
        position <= 0 -> RowGroup.Top
        position >= count - 1 -> RowGroup.Bottom
        else -> RowGroup.Middle
    }

/** The two radii a row of this group wears: its own edges take the outer token, the shared one the inner. */
fun Context.groupedRowRadii(
    group: RowGroup,
    corners: RowCorners,
): Pair<Float, Float> {
    val outer = cornerSize(corners.outer.attr, corners.outer.style, corners.outer.fallback)
    val inner = cornerSize(corners.inner.attr, corners.inner.style, corners.inner.fallback)
    return when (group) {
        RowGroup.Single -> outer to outer
        RowGroup.Top -> outer to inner
        RowGroup.Middle -> inner to inner
        RowGroup.Bottom -> inner to outer
    }
}

/** The shape those radii make, for whatever draws with them. */
fun Context.groupedRowShape(
    group: RowGroup,
    corners: RowCorners,
): ShapeAppearanceModel {
    val (top, bottom) = groupedRowRadii(group, corners)
    return ShapeAppearanceModel
        .builder()
        .setTopLeftCorner(CornerFamily.ROUNDED, top)
        .setTopRightCorner(CornerFamily.ROUNDED, top)
        .setBottomLeftCorner(CornerFamily.ROUNDED, bottom)
        .setBottomRightCorner(CornerFamily.ROUNDED, bottom)
        .build()
}

/**
 * The press of a row that lives inside one surface, such as a card that opens: only the ends that
 * meet the page are rounded. A joint inside the surface is square, because there is no gap there and
 * a rounded joint would cut a notch out of the highlight.
 */
fun Context.groupedInnerPress(
    topIsRound: Boolean,
    bottomIsRound: Boolean,
    corners: RowCorners,
): Drawable {
    val (outer, inner) = groupedRowRadii(RowGroup.Single, corners)
    return RippleDrawable(
        ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight)),
        null,
        MaterialShapeDrawable(groupedRowShape(RowGroup.Single, corners)).apply {
            shapeAppearanceModel =
                ShapeAppearanceModel
                    .builder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, if (topIsRound) outer else inner)
                    .setTopRightCorner(CornerFamily.ROUNDED, if (topIsRound) outer else inner)
                    .setBottomLeftCorner(CornerFamily.ROUNDED, if (bottomIsRound) outer else inner)
                    .setBottomRightCorner(CornerFamily.ROUNDED, if (bottomIsRound) outer else inner)
                    .build()
            fillColor = ColorStateList.valueOf(Color.WHITE)
        },
    )
}

/** The press, and only the press: a ripple masked to the shape a row of this group has. */
fun Context.groupedRowRipple(
    group: RowGroup,
    corners: RowCorners = RowCorners.Cards,
): Drawable =
    RippleDrawable(
        ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight)),
        null,
        MaterialShapeDrawable(groupedRowShape(group, corners)).apply {
            fillColor = ColorStateList.valueOf(Color.WHITE)
        },
    )

/**
 * A row of a hand built group, surface and press together: the page itself draws no cards. The rows
 * a settings list is made of ask for the press alone, because their surface is drawn for them.
 */
fun Context.groupedRowBackground(
    group: RowGroup,
    corners: RowCorners = RowCorners.Cards,
): Drawable =
    RippleDrawable(
        ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight)),
        MaterialShapeDrawable(groupedRowShape(group, corners)).apply {
            fillColor =
                ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorSurfaceBright),
                )
        },
        MaterialShapeDrawable(groupedRowShape(group, corners)).apply {
            fillColor = ColorStateList.valueOf(Color.WHITE)
        },
    )

/** The same, for a row that knows its place by position. */
fun Context.groupedRowBackground(
    position: Int,
    count: Int,
    corners: RowCorners = RowCorners.Cards,
): Drawable = groupedRowBackground(groupedRowGroup(position, count), corners)
