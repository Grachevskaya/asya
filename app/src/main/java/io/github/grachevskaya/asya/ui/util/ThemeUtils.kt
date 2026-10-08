package io.github.grachevskaya.asya.ui.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.resources.MaterialResources
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.ui.util.FixedSizeDrawable

object ThemeUtils {
    private val colorThemeMap =
        mapOf(
            "SAKURA" to R.style.ThemeOverlay_Light_MaterialSakura,
            "MATERIAL_RED" to R.style.ThemeOverlay_Light_MaterialRed,
            "MATERIAL_PINK" to R.style.ThemeOverlay_Light_MaterialPink,
            "MATERIAL_PURPLE" to R.style.ThemeOverlay_Light_MaterialPurple,
            "MATERIAL_DEEP_PURPLE" to R.style.ThemeOverlay_Light_MaterialDeepPurple,
            "MATERIAL_INDIGO" to R.style.ThemeOverlay_Light_MaterialIndigo,
            "MATERIAL_BLUE" to R.style.ThemeOverlay_Light_MaterialBlue,
            "MATERIAL_LIGHT_BLUE" to R.style.ThemeOverlay_Light_MaterialLightBlue,
            "MATERIAL_CYAN" to R.style.ThemeOverlay_Light_MaterialCyan,
            "MATERIAL_TEAL" to R.style.ThemeOverlay_Light_MaterialTeal,
            "MATERIAL_GREEN" to R.style.ThemeOverlay_Light_MaterialGreen,
            "MATERIAL_LIGHT_GREEN" to R.style.ThemeOverlay_Light_MaterialLightGreen,
            "MATERIAL_LIME" to R.style.ThemeOverlay_Light_MaterialLime,
            "MATERIAL_YELLOW" to R.style.ThemeOverlay_Light_MaterialYellow,
            "MATERIAL_AMBER" to R.style.ThemeOverlay_Light_MaterialAmber,
            "MATERIAL_ORANGE" to R.style.ThemeOverlay_Light_MaterialOrange,
            "MATERIAL_DEEP_ORANGE" to R.style.ThemeOverlay_Light_MaterialDeepOrange,
            "MATERIAL_BROWN" to R.style.ThemeOverlay_Light_MaterialBrown,
            "MATERIAL_BLUE_GREY" to R.style.ThemeOverlay_Light_MaterialBlueGrey,
        )

    private val darkColorThemeMap =
        mapOf(
            "SAKURA" to R.style.ThemeOverlay_Dark_MaterialSakura,
            "MATERIAL_RED" to R.style.ThemeOverlay_Dark_MaterialRed,
            "MATERIAL_PINK" to R.style.ThemeOverlay_Dark_MaterialPink,
            "MATERIAL_PURPLE" to R.style.ThemeOverlay_Dark_MaterialPurple,
            "MATERIAL_DEEP_PURPLE" to R.style.ThemeOverlay_Dark_MaterialDeepPurple,
            "MATERIAL_INDIGO" to R.style.ThemeOverlay_Dark_MaterialIndigo,
            "MATERIAL_BLUE" to R.style.ThemeOverlay_Dark_MaterialBlue,
            "MATERIAL_LIGHT_BLUE" to R.style.ThemeOverlay_Dark_MaterialLightBlue,
            "MATERIAL_CYAN" to R.style.ThemeOverlay_Dark_MaterialCyan,
            "MATERIAL_TEAL" to R.style.ThemeOverlay_Dark_MaterialTeal,
            "MATERIAL_GREEN" to R.style.ThemeOverlay_Dark_MaterialGreen,
            "MATERIAL_LIGHT_GREEN" to R.style.ThemeOverlay_Dark_MaterialLightGreen,
            "MATERIAL_LIME" to R.style.ThemeOverlay_Dark_MaterialLime,
            "MATERIAL_YELLOW" to R.style.ThemeOverlay_Dark_MaterialYellow,
            "MATERIAL_AMBER" to R.style.ThemeOverlay_Dark_MaterialAmber,
            "MATERIAL_ORANGE" to R.style.ThemeOverlay_Dark_MaterialOrange,
            "MATERIAL_DEEP_ORANGE" to R.style.ThemeOverlay_Dark_MaterialDeepOrange,
            "MATERIAL_BROWN" to R.style.ThemeOverlay_Dark_MaterialBrown,
            "MATERIAL_BLUE_GREY" to R.style.ThemeOverlay_Dark_MaterialBlueGrey,
        )

    val isSystemAccent get() = DynamicColors.isDynamicColorAvailable() && PrefManager.followSystemAccent

    fun isNightMode(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @StyleRes
    fun getOverlayThemeStyleRes(context: Context): Int = R.style.ThemeOverlay

    val colorTheme get() = if (isSystemAccent) "SYSTEM" else PrefManager.themeColor

    @StyleRes
    fun getColorThemeStyleRes(context: Context): Int =
        if (isNightMode(context)) {
            darkColorThemeMap[colorTheme] ?: R.style.ThemeOverlay_Dark_MaterialBlue
        } else {
            colorThemeMap[colorTheme] ?: R.style.ThemeOverlay_Light_MaterialBlue
        }

    /**
     * A colour role out of the current theme. The resolving is the library's: MaterialColors walks
     * the theme and its overlays the way the Material themes are built to be walked, which is not
     * something a spare obtainStyledAttributes call gets right for a role that comes from an
     * overlay rather than from the theme itself.
     */
    @ColorInt
    fun Context.themeColor(
        @AttrRes themeAttrId: Int,
    ): Int = MaterialColors.getColor(this, themeAttrId, Color.MAGENTA)

    @ColorInt
    fun Fragment.themeColor(
        @AttrRes themeAttrId: Int,
    ) = requireContext().themeColor(themeAttrId)

    /**
     * The same for a drawable role. The lookup is still one styled-attributes call, because that is
     * what resolving an attribute is; what the library brings is the rest -- the tint, the theme
     * the drawable is handed, and the caching -- which a bare getDrawable(0) does not.
     */
    fun Context.attrDrawable(
        @AttrRes themeAttrId: Int,
    ): Drawable? {
        val attributes = obtainStyledAttributes(intArrayOf(themeAttrId))
        //noinspection RestrictedApi
        val drawable = MaterialResources.getDrawable(this, attributes, 0)
        attributes.recycle()
        return drawable
    }

    fun Fragment.attrDrawable(
        @AttrRes themeAttrId: Int,
    ) = requireContext().attrDrawable(themeAttrId)

    @ColorInt
    fun Fragment.getColor(
        @ColorRes colorId: Int,
    ) = requireContext().getColor(colorId)

    fun Context.homeItemBackgroundColor(forceNoTrans: Boolean = false) =
        (
            if (isNightMode(this)) {
                themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
            } else {
                themeColor(com.google.android.material.R.attr.colorSurfaceContainer)
            }
        ).let {
            return@let it
        }

    fun Fragment.homeItemBackgroundColor(forceNoTrans: Boolean = false) = requireContext().homeItemBackgroundColor(forceNoTrans)

    fun Int.asDrawable(context: Context) = ResourcesCompat.getDrawable(context.resources, this, context.theme)!!

    fun Int.asDrawable(
        context: Context,
        sizeDp: Int,
    ) = FixedSizeDrawable(asDrawable(context), sizeDp.dpToPx.toInt())

    fun Int.asColor(context: Context) = ResourcesCompat.getColor(context.resources, this, context.theme)

    /**
     * A corner size from the shape system the theme carries, so a surface drawn in code is rounded
     * like the widgets beside it instead of like a number written here. The Material 3 expressive
     * themes set the whole family; [fallbackDp] is the value this file used before the theme was
     * the one deciding.
     */
    fun Context.cornerSize(
        @AttrRes attrRes: Int,
        @StyleRes styleRes: Int,
        fallbackDp: Int,
    ): Float {
        /*
         * The value is taken from the theme only when it really is a dimension. A corner token a
         * theme points at a style -- which the Material themes do -- is a reference, and reading it
         * as a dimension throws; the shape system's own style for that corner is then the answer,
         * and only a theme with neither leaves the number this file used before.
         */
        val typed = TypedValue()
        val attributes = obtainStyledAttributes(intArrayOf(attrRes))
        val isDimension = attributes.getValue(0, typed) && typed.type == TypedValue.TYPE_DIMENSION
        attributes.recycle()

        if (isDimension) return typed.getDimension(resources.displayMetrics)

        // The shape system keeps the same corner as a style with cornerSize in it, which is a
        // dimension and can be read as one. The Material themes point the tokens at these styles
        // precisely so that a theme can change the family without changing every caller.
        val corners = obtainStyledAttributes(styleRes, intArrayOf(com.google.android.material.R.attr.cornerSize))
        val size = corners.getDimension(0, fallbackDp.dpToPx)
        corners.recycle()

        return size
    }

    fun Fragment.cornerSize(
        @AttrRes attrRes: Int,
        @StyleRes styleRes: Int,
        fallbackDp: Int,
    ) = requireContext().cornerSize(attrRes, styleRes, fallbackDp)
}
