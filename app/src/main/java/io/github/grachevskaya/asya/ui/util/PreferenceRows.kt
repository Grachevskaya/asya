package io.github.grachevskaya.asya.ui.util

import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.MaterialShapeDrawable
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor

/** The radius a group's own edges wear, and the one the rows between them wear. */

/** Where the row holding this view sits in its group, asked of the list it lives in. */
@android.annotation.SuppressLint("RestrictedApi")
fun View.rowGroupInList(): RowGroup {
    val list = parent as? RecyclerView ?: return RowGroup.Single
    //noinspection RestrictedApi
    val adapter = list.adapter as? PreferenceGroupAdapter ?: return RowGroup.Single
    val position = list.getChildAdapterPosition(this)
    if (position == RecyclerView.NO_POSITION) return RowGroup.Single

    fun isGroupTitle(at: Int) = adapter.getItem(at) is PreferenceCategory

    var first = position
    while (first > 0 && !isGroupTitle(first - 1)) first--
    var last = position
    while (last + 1 < adapter.itemCount && !isGroupTitle(last + 1)) last++

    return when {
        first == last -> RowGroup.Single
        position == first -> RowGroup.Top
        position == last -> RowGroup.Bottom
        else -> RowGroup.Middle
    }
}

/**
 * The rows a settings screen is made of, in the language the rest of the manager speaks: one surface
 * per group, the first and last row rounded on the outside and the ones between them on the inside
 * only, with the group's title standing on the page between the groups.
 *
 * The surface is drawn by a decoration rather than set on the row. A preference puts its own
 * background on a row every time it binds one -- a switch being toggled is exactly such a moment --
 * so a background set here is gone for a frame, which is the flash that was seen. What the decoration
 * draws, nothing else touches, and the row's own background stays what it is: the press.
 */
@android.annotation.SuppressLint("RestrictedApi")
fun PreferenceFragmentCompat.stylePreferenceRows() {
    val list = listView
    //noinspection RestrictedApi
    val adapter = list.adapter as? PreferenceGroupAdapter ?: return
    val inset = 16.dpToPx.toInt()
    val card =
        MaterialShapeDrawable().apply {
            fillColor =
                android.content.res.ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorSurfaceBright),
                )
        }

    list.setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
    list.setPadding(0, 0, 0, inset)
    list.clipToPadding = false
    // A row that opens its card makes the screen scrollable, and the list’s own bar then turns up
    // in the gutter beside that card. Material draws no such bar, and the rows say where you are.
    list.isVerticalScrollBarEnabled = false
    while (list.itemDecorationCount > 0) {
        list.removeItemDecorationAt(0)
    }

    //noinspection RestrictedApi
    fun isGroupTitle(position: Int) = position != RecyclerView.NO_POSITION && adapter.getItem(position) is PreferenceCategory

    /** Where the row at [position] sits in its group, which is what decides its corners. */
    fun groupOf(position: Int): RowGroup {
        var first = position
        while (first > 0 && !isGroupTitle(first - 1)) first--
        var last = position
        //noinspection RestrictedApi
        while (last + 1 < adapter.itemCount && !isGroupTitle(last + 1)) last++

        return when {
            first == last -> RowGroup.Single
            position == first -> RowGroup.Top
            position == last -> RowGroup.Bottom
            else -> RowGroup.Middle
        }
    }

    list.addItemDecoration(
        object : RecyclerView.ItemDecoration() {
            /*
             * The inset is an offset and not a margin on the row: a margin written after the row has
             * been measured does not reach that row's next layout pass, so the row keeps the width it
             * was given and slides past the screen.
             */
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State,
            ) {
                val position = parent.getChildAdapterPosition(view)

                outRect.left = inset
                outRect.right = inset
                outRect.top = if (isGroupTitle(position)) 10.dpToPx.toInt() else 1.dpToPx.toInt()
                outRect.bottom = if (isGroupTitle(position)) 0 else 1.dpToPx.toInt()
            }

            override fun onDraw(
                canvas: Canvas,
                parent: RecyclerView,
                state: RecyclerView.State,
            ) {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChildAt(index)
                    val position = parent.getChildAdapterPosition(child)
                    if (position == RecyclerView.NO_POSITION || isGroupTitle(position)) continue

                    card.shapeAppearanceModel =
                        parent.context.groupedRowShape(groupOf(position), RowCorners.List)
                    card.setBounds(child.left, child.top, child.right, child.bottom)
                    card.draw(canvas)
                }
            }
        },
    )

    fun style(child: View) {
        val position = list.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION || isGroupTitle(position)) {
            child.background = null
            return
        }

        child.background = list.context.groupedRowRipple(groupOf(position), RowCorners.List)
    }

    list.addOnChildAttachStateChangeListener(
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = style(view)

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        },
    )

    val restyle =
        Runnable {
            for (index in 0 until list.childCount) style(list.getChildAt(index))
        }

    if (styledLists[list] == null) {
        val observer =
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = restyleLater()

                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                ) = restyleLater()

                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) = restyleLater()

                override fun onItemRangeRemoved(
                    positionStart: Int,
                    itemCount: Int,
                ) = restyleLater()

                override fun onItemRangeMoved(
                    fromPosition: Int,
                    toPosition: Int,
                    itemCount: Int,
                ) = restyleLater()

                private fun restyleLater() {
                    list.post(restyle)
                }
            }

        styledLists[list] = observer
        list.adapter?.registerAdapterDataObserver(observer)
    }

    restyle.run()
}

/** One observer per list, however many times a screen is shown again. */
private val styledLists = java.util.WeakHashMap<RecyclerView, RecyclerView.AdapterDataObserver>()
