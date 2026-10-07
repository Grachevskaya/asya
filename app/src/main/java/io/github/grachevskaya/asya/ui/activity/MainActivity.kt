package io.github.grachevskaya.asya.ui.activity

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import io.github.grachevskaya.asya.BuildConfig
import io.github.grachevskaya.asya.MyApp.Companion.asyaApp
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.ActivityMainBinding
import io.github.grachevskaya.asya.host.HostContext
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.ThemeUtils
import io.github.grachevskaya.asya.util.ConfigUtils

/**
 * The manager's main screen.
 *
 * The three parameters are the way a hosted instance is built, and they are not used in the app
 * itself: the framework asks for the no-argument constructor (which @JvmOverloads generates
 * alongside), and the module, when it shows this from inside a host process, asks for the one that
 * takes the resources of the apk it loaded -- the shape Sui's activity has, for the same reason.
 * A hosted instance is built on those resources instead of the host's.
 */
class MainActivity
    @JvmOverloads
    constructor(
        application: Application? = null,
        hostedResources: Resources? = null,
        @Suppress("unused") component: ComponentName? = null,
    ) : AppCompatActivity() {
        var readyToKill: Boolean = true
        var currentConfiguration: Configuration? = null

        /** The page on screen, kept as it changes: a rebuild comes back to it. */
        private var lastDestination = 0

        /** The page the bar carries that a rebuild has to come back to, if there is one. */
        private fun rememberedStart(): Int? = lastDestination.takeIf { it != 0 && it in BOTTOM_BAR_PLACES && it != R.id.nav_home }

        /**
         * The host, built here and not named in the layout.
         *
         * A host the framework is handed is a host that comes back with the page it was showing, and
         * its graph is then whatever was saved with it -- which is why every rebuild landed on the home
         * page, however the start was asked for. A host built here has exactly the graph this code
         * gives it, so any of the bar's pages can be the one it starts on.
         */
        private fun ensureNavHost(startAt: Int?): NavHostFragment {
            val existing = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            if (existing is NavHostFragment) return existing

            val host = NavHostFragment.create(R.navigation.home_nav_graph)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.nav_host_fragment, host, NAV_HOST_TAG)
                .commitNow()

            if (startAt != null) {
                val graph = host.navController.navInflater.inflate(R.navigation.home_nav_graph)
                graph.setStartDestination(startAt)
                host.navController.setGraph(graph, null)
            }

            return host
        }

        internal companion object {
            /** The page the next Activity should restore after a normal rebuild. */
            var pendingDestination = 0
            var fadeInOnCreate = false

            const val NAV_HOST_TAG = "asya-nav-host"

            val BOTTOM_BAR_PLACES =
                setOf(
                    R.id.nav_home,
                    R.id.nav_app_manage,
                    R.id.nav_template_manage,
                    R.id.nav_logs,
                    R.id.nav_settings,
                )
        }

        private val hosted: HostContext? =
            if (application != null && hostedResources != null) {
                HostContext(application, hostedResources)
            } else {
                null
            }

        /**
         * The icon for the task description, as a bitmap.
         *
         * The drawable has to be drawn and not decoded: the launcher icon is an adaptive icon, which is
         * a description of layers rather than a picture, and BitmapFactory.decodeResource answers null
         * for it. The task description then went without an icon and the recents entry fell back to the
         * host's -- the right name with the wrong face.
         */
        /**
         * The inflater every view in here is built with, on the manager's own resources.
         *
         * The activity's own inflater is made while attach runs, before the context is replaced, so its
         * resource table is the host's. A fragment whose layout happens not to collide with something in
         * that table inflates anyway and looks fine; one whose layout does collide -- or does not exist
         * there at all -- dies with a Resources$NotFoundException naming one of this module's resources. Building the
         * inflater here means every layout in the manager is read from the apk it came in.
         */

        /** The inflater built on the manager's own context, for the layouts below. */
        private val hostedInflater: android.view.LayoutInflater? by lazy {
        /*
         * Built on this activity, not on the hosted context: the context an inflater gives its views
         * is the one the tree is searched through, and FragmentContainerView refuses to build a
         * NavHostFragment unless it finds a FragmentActivity above it. The hosted context's base is
         * the host, so that search ended nowhere. Here the views see this activity, and this
         * activity's resources are the hosted ones -- the ids still resolve in the apk.
         */
            hosted?.let {
                io.github.grachevskaya.asya.host
                    .HostedInflater(this)
            }
        }

        /**
         * The window asks the activity for its inflater by service name, and a context wrapper passes
         * anything it does not know straight down the chain, which would reach the host's inflater, on
         * the host's resources. Handing back the hosted one keeps everything the window
         * builds, and everything AppCompat installs its view factory on, on the apk.
         */
        override fun getSystemService(name: String): Any? =
            if (name == android.content.Context.LAYOUT_INFLATER_SERVICE) {
                hostedInflater ?: super.getSystemService(name)
            } else {
                super.getSystemService(name)
            }

        private fun taskIcon(): android.graphics.Bitmap {
            val size = resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
            val drawable = appIcon() ?: android.graphics.drawable.ColorDrawable(0)

            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    size,
                    size,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)

            return bitmap
        }

        /**
         * The app's own icon, the way a launcher draws it.
         *
         * The launcher icon is an adaptive icon, and an adaptive icon is an xml file. Inflating it can
         * fail in a hosted process, and it fails by throwing rather than by answering null. The parts
         * that xml names are
         * ordinary resources, though: a background colour and a foreground picture. Building the icon
         * from those with the platform's own AdaptiveIconDrawable gives the app its own icon --
         * the same two layers, the same geometry, the same mask the launcher applies -- rather than a
         * stand-in for it. The last resort below is only that: the icon is what comes back first.
         */
        private fun appIcon(): android.graphics.drawable.Drawable? {
            runCatching {
                androidx.core.content.ContextCompat
                    .getDrawable(this, R.mipmap.ic_launcher)
            }.getOrNull()?.let { return it }

            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return null

            val background =
                runCatching {
                    androidx.core.content.ContextCompat
                        .getDrawable(this, R.color.ic_launcher_background)
                }.getOrNull() ?: return null

            val foreground =
                runCatching {
                    androidx.core.content.ContextCompat
                        .getDrawable(this, R.mipmap.ic_launcher_fg)
                }.getOrNull() ?: return null

            return android.graphics.drawable.AdaptiveIconDrawable(background, foreground)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            hosted?.let { setTheme(it.appThemeStyle()) }

            setTaskDescription(
                android.app.ActivityManager.TaskDescription(
                    getString(R.string.app_name),
                    taskIcon(),
                    getColor(R.color.ic_launcher_background),
                ),
            )
            super.onCreate(null)

            enableEdgeToEdge()
            applyColorOverlays()

            currentConfiguration = resources.configuration

            val binding = ActivityMainBinding.inflate(hostedInflater ?: layoutInflater)
            setContentView(binding.root)

            // The host is built here, and it starts on the page a rebuild has to come back to. The
            // page comes from the field the rebuild kept, and not from one of this instance's: a new
            // instance has no memory of where the last one was, which is how this landed on the home
            // page every time.
            val startAt = pendingDestination.takeIf { it != 0 && it in BOTTOM_BAR_PLACES && it != R.id.nav_home }
            pendingDestination = 0

            setupNavigation(binding, ensureNavHost(startAt))
        }

        /**
         * The colour part of the theme: the system's own colours where the platform has them, and the
         * chosen accent over them otherwise.
         */
        private fun applyColorOverlays() {
            // I add this manually because the E2E code is not working like I want
            // They should give us a separate method to set it for enableEdgeToEdge
            // Source: https://github.com/androidx/androidx/blob/c0f9aabcf6f32029249ac7647711744b68e2a003/activity/activity/src/main/java/androidx/activity/EdgeToEdge.kt#L299

            if (DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.applyToActivityIfAvailable(
                    this,
                    DynamicColorsOptions
                        .Builder()
                        .also {
                            if (!ThemeUtils.isSystemAccent) {
                                it.setThemeOverlay(ThemeUtils.getColorThemeStyleRes(this))
                            }
                        }.build(),
                )
            } else {
                theme.applyStyle(ThemeUtils.getColorThemeStyleRes(this), true)
            }
        }

        /** Save the current page and suppress the host window transition before rebuilding. */
        private fun prepareRecreate() {
            pendingDestination = lastDestination.takeIf { it != 0 } ?: currentDestination()

            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        /** Theme resources are read while views are inflated, so rebuild the hosted Activity normally. */
        internal fun applyThemeInPlace() {
            readyToKill = false
            prepareRecreate()
            recreate()
        }

        /** AppCompat changes the configuration; the callback below performs the same rebuild. */
        internal fun applyNightMode(mode: Int) {
            readyToKill = false
            prepareRecreate()
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        private fun currentDestination(): Int =
            runCatching {
                val content = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                navHostIn(content)?.navController?.currentDestination?.id ?: 0
            }.getOrDefault(0)

        /**
         * The host that belongs to this content.
         *
         * There are two of them for the moment a rebuild lasts -- the one being taken away and the one
         * taking its place -- so which is which is a question about the view tree, not about an id.
         */
        private fun navHostIn(root: View): NavHostFragment? =
            supportFragmentManager.fragments
                .filterIsInstance<NavHostFragment>()
                .firstOrNull { fragment ->
                    val view = fragment.view ?: return@firstOrNull false
                    view === root || isInside(root, view)
                }

        private fun isInside(
            root: View,
            view: View,
        ): Boolean {
            var parent: android.view.ViewParent? = view.parent

            while (parent is View) {
                if (parent === root) return true
                parent = parent.parent
            }

            return false
        }

        /** The bar, wired to whatever host is on screen now. */
        private fun setupNavigation(
            binding: ActivityMainBinding,
            host: NavHostFragment,
        ) {
            val controller = host.navController
            val bottomNav = binding.bottomNav
            bottomNav.setupWithNavController(controller)
            controller.addOnDestinationChangedListener { _, destination, _ ->
                bottomNav.isVisible = destination.id in BOTTOM_BAR_PLACES
                lastDestination = destination.id
            }

            if (fadeInOnCreate) {
                fadeInOnCreate = false

                binding.root.alpha = 0f
                binding.root
                    .animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .start()
            }
        }

        override fun onApplyThemeResource(
            theme: Resources.Theme,
            resid: Int,
            first: Boolean,
        ) {
            super.onApplyThemeResource(theme, resid, first)

            theme.applyStyle(ThemeUtils.getOverlayThemeStyleRes(this), true)
        }

        override fun onSaveInstanceState(outState: Bundle) {
            // Nothing is saved, because the host is what would restore it: the screen is rebuilt.
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)

            if (isNightModeEnabled(currentConfiguration) != isNightModeEnabled(newConfig)) {
                readyToKill = false
                applyThemeInPlace()
            }

            currentConfiguration = newConfig
        }

        override fun onSupportNavigateUp(): Boolean {
            val navController = this.findNavController(R.id.nav_host_fragment)
            return navController.navigateUp() || super.onSupportNavigateUp()
        }

        /**
         * The one place the activity's context is decided. Hosted, it is the apk's resources and the
         * apk's theme; on its own, it is the app's own context with the locale applied, as before.
         */
        override fun attachBaseContext(newBase: Context?) {
            val base = hosted ?: newBase
            super.attachBaseContext(getLocaleAppliedContext(base))
        }

        override fun onDestroy() {
            if (readyToKill) {
                ServiceClient.forceStop(BuildConfig.APPLICATION_ID)
            } else {
                readyToKill = true
            }

            super.onDestroy()
        }

        private fun getLocaleAppliedContext(context: Context?): Context? {
            val config = asyaApp.resources.configuration
            config.setLocale(ConfigUtils.getLocale())

            return context?.createConfigurationContext(config)
        }

        private fun isNightModeEnabled(config: Configuration?) = config?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
    }
