package io.github.grachevskaya.asya.ui.util

import android.content.ContentResolver
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.MenuRes
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.ui.activity.MainActivity

val Fragment.navController get() = NavHostFragment.findNavController(this)

private val navOptions by lazy {
    NavOptions
        .Builder()
        .apply {
            setEnterAnim(R.anim.activity_open_enter)
            setExitAnim(R.anim.activity_open_exit)
            setPopEnterAnim(R.anim.activity_close_enter)
            setPopExitAnim(R.anim.activity_close_exit)
        }.build()
}

fun Fragment.navigate(
    @IdRes resId: Int,
    args: Bundle? = null,
) {
    val cr = requireContext().contentResolver
    if (AccessibilityUtils.isAnimationEnabled(cr)) {
        navController.navigate(resId, args, navOptions)
    } else {
        navController.navigate(resId, args)
    }
}

fun Fragment.setupToolbar(
    toolbar: Toolbar,
    title: String,
    subtitle: String? = null,
    @DrawableRes navigationIcon: Int? = null,
    navigationOnClick: View.OnClickListener? = null,
    @MenuRes menuRes: Int? = null,
    onMenuOptionSelected: ((MenuItem) -> Unit)? = null,
) {
    navigationOnClick?.let { toolbar.setNavigationOnClickListener(it) }
    navigationIcon?.let { toolbar.setNavigationIcon(navigationIcon) }
    toolbar.title = title
    if (subtitle != null) toolbar.subtitle = subtitle
    toolbar.tooltipText = title
    if (menuRes != null) {
        val menuProvider =
            object : MenuProvider {
                override fun onCreateMenu(
                    menu: Menu,
                    menuInflater: MenuInflater,
                ) {
                    menuInflater.inflate(menuRes, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                    onMenuOptionSelected?.let {
                        it(menuItem)
                        true
                    } ?: false
            }
        toolbar.menu.clear()
        toolbar.inflateMenu(menuRes)
        toolbar.setOnMenuItemClickListener(menuProvider::onMenuItemSelected)
        requireActivity().addMenuProvider(menuProvider)
        menuProvider.onPrepareMenu(toolbar.menu)
    }
}

val Fragment.contentResolver get(): ContentResolver = requireContext().contentResolver

/**
 * Applies a change that can only be applied by building the screen again -- a theme, a locale.
 *
 * The page is remembered first: a recreate keeps nothing, so without that the user was returned to
 * the home page from wherever they had been. The screen fades out and the new one fades in, which is
 * what makes the change read as a theme being applied rather than as a page being left.
 */

/**
 * The half of a rebuild that has to come first, whatever starts the rebuild: the page to come
 * back to, and the flag that keeps the destroy from being read as a request to force stop the app.
 * A rebuild AppCompat starts on its own -- a language change -- needs both just as much as the one
 * below, and without the flag the app really did stop and start again.
 */
fun Fragment.rememberDestinationForRebuild() {
    val mainActivity = activity as MainActivity
    mainActivity.readyToKill = false

    MainActivity.pendingDestination = navController.currentDestination?.id ?: 0
    MainActivity.fadeInOnCreate = true
}

fun Fragment.recreateMainActivity(restart: Boolean = false) {
    rememberDestinationForRebuild()

    val mainActivity = activity as MainActivity

    if (restart) {
        mainActivity.finish()
        startActivity(Intent(mainActivity, mainActivity.javaClass))
        return
    }

    fadeOutThen { mainActivity.recreate() }
}

/**
 * The cover a rebuild happens behind: the screen fades out and the action runs at its end.
 *
 * The fade is not decoration. A relaunch takes a moment in which nothing of this activity is on
 * screen, and the window behind it -- the host, the launcher -- shows through that moment. It is
 * also why a rebuild started by something else, AppCompat applying a language, still comes here:
 * one relaunch, hidden, instead of one that shows the desktop on the way.
 */
fun Fragment.fadeOutThen(action: () -> Unit) {
    val mainActivity = activity as MainActivity
    val content =
        mainActivity.findViewById<View>(android.R.id.content) ?: mainActivity.window.decorView

    content
        .animate()
        .alpha(0f)
        .setDuration(140L)
        .withEndAction(action)
        .start()
}

fun FragmentTransaction.withAnimations() =
    setCustomAnimations(
        R.anim.activity_open_enter,
        R.anim.activity_open_exit,
        R.anim.activity_close_enter,
        R.anim.activity_close_exit,
    )

@Suppress("DEPRECATION")
fun setEdge2EdgeFlags(
    root: View,
    left: Int? = null,
    top: Int? = null,
    right: Int? = null,
    bottom: Int? = null,
    getInsets: ((left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null,
) {
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val barInsets = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(
                left ?: barInsets.left,
                top ?: barInsets.top,
                right ?: barInsets.right,
                bottom ?: barInsets.bottom,
            )

            getInsets?.invoke(
                barInsets.left,
                barInsets.top,
                barInsets.right,
                barInsets.bottom,
            )
        } else {
            v.setPadding(
                left ?: insets.systemWindowInsetLeft,
                top ?: insets.systemWindowInsetTop,
                right ?: insets.systemWindowInsetRight,
                bottom ?: insets.systemWindowInsetBottom,
            )

            getInsets?.invoke(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }

        insets
    }
}

fun Fragment.registerOnBackCallback(back: () -> Unit) = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { back() }
