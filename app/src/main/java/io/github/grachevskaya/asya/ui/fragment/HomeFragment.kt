package io.github.grachevskaya.asya.ui.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.SystemClock.elapsedRealtime
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.MyApp.Companion.hmaApp
import io.github.grachevskaya.asya.common.Constants.ENABLE_INTERNET_OFF
import io.github.grachevskaya.asya.common.Constants.ENABLE_INTERNET_ON
import io.github.grachevskaya.asya.common.Constants.ENABLE_INTERNET_UNKNOWN
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_CRASHED
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_LOADING
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_NO_HOOKS
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_UNKNOWN
import io.github.grachevskaya.asya.data.fetchLatestUpdate
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.ThemeUtils.attrDrawable
import io.github.grachevskaya.asya.ui.util.ThemeUtils.getColor
import io.github.grachevskaya.asya.ui.util.ThemeUtils.homeItemBackgroundColor
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor
import io.github.grachevskaya.asya.ui.util.dpToPx
import io.github.grachevskaya.asya.ui.util.isTestBuild
import io.github.grachevskaya.asya.ui.util.navigate
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.grachevskaya.asya.BuildConfig
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.FragmentHomeBinding
import kotlin.concurrent.thread

/**
 * A simple [Fragment] subclass.
 * Use the [HomeFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private val binding by viewBinding(FragmentHomeBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.app_name),
                menuRes = R.menu.menu_home,
                onMenuOptionSelected = ::onMenuOptionSelected,
            )
            // isTitleCentered = true

            setOnLongClickListener {
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.app_name)
                    .create()

                dialog.setView(Chronometer(context).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -2)
                    base = elapsedRealtime() + 3000
                    textSize = 24.dpToPx
                    gravity = Gravity.CENTER
                    typeface = Typeface.SERIF
                    onChronometerTickListener = {
                        if (elapsedRealtime() >= base) {
                            stop()
                            dialog.dismiss()

                            // is it really final countdown?
                            isTheFinalCountDown
                        }
                    }
                    isCountDown = true
                    start()
                })

                dialog.show()

                true
            }
        }

        setEdge2EdgeFlags(binding.root)
    }

    override fun onStart() {
        super.onStart()

        waitForService()

        with(binding.howToUse.root.parent as ViewGroup) {
            val childCount = childCount

            val softCorner = 24.dpToPx
            val squareCorner = 8.dpToPx
            val pad = 16.dpToPx.toInt()

            for (i in 0..< childCount) {
                getChildAt(i).apply {
                    (this as ViewGroup).apply {
                        val textColor = themeColor(
                            com.google.android.material.R.attr.colorOnSurface,
                        )

                        findViewById<TextView>(android.R.id.text1).setTextColor(textColor)
                        findViewById<ImageView>(android.R.id.icon).setColorFilter(textColor)
                    }

                    (layoutParams as LinearLayout.LayoutParams).apply {
                        setMargins(pad, 0, pad, 0)
                    }

                    val backgroundDrawable = GradientDrawable()
                    backgroundDrawable.setColor(homeItemBackgroundColor())

                    when (i) {
                        0 -> {
                            backgroundDrawable.cornerRadii = floatArrayOf(
                                softCorner,
                                softCorner,
                                softCorner,
                                softCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner
                            )
                        }
                        childCount - 1 -> {
                            backgroundDrawable.cornerRadii = floatArrayOf(
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                softCorner,
                                softCorner,
                                softCorner,
                                softCorner
                            )
                        }
                        else -> {
                            backgroundDrawable.cornerRadii = floatArrayOf(
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner
                            )
                        }
                    }

                    val ripple = attrDrawable(android.R.attr.selectableItemBackground)
                    val layerDrawable = LayerDrawable(arrayOf(
                        backgroundDrawable,
                        ripple,
                    ))

                    background = layerDrawable
                    clipToOutline = true
                }

            }
        }

        with(binding.howToUse) {
            text1.text = getString(R.string.about_how_to_use_title)
            icon.setImageResource(R.drawable.baseline_help_outline_24)
            root.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.about_how_to_use_title)
                    .setMessage(
                        getString(R.string.about_how_to_use_description_1) +
                                "\n\n" +
                                getString(R.string.about_how_to_use_description_2) +
                                "\n\n" +
                                getString(R.string.about_how_to_use_description_3))
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }

        with(binding.manageApps) {
            text1.text = getString(R.string.title_app_manage)
            icon.setImageResource(R.drawable.outline_android_24)
            root.setOnClickListener {
                navigate(R.id.nav_app_manage)
            }
        }

        with(binding.manageTemplates) {
            text1.text = getString(R.string.title_template_manage)
            icon.setImageResource(R.drawable.ic_outline_layers_24)
            root.setOnClickListener {
                navigate(R.id.nav_template_manage)
            }
        }

        with(binding.navLogs) {
            text1.text = getString(R.string.title_logs)
            icon.setImageResource(R.drawable.outline_assignment_24)
            root.setOnClickListener {
                navigate(R.id.nav_logs)
            }
        }

        with(binding.navSettings) {
            text1.text = getString(R.string.title_settings)
            icon.setImageResource(R.drawable.outline_settings_24)
            root.setOnClickListener {
                navigate(R.id.nav_settings)
            }
        }

        with(binding.navAbout) {
            text1.text = getString(R.string.title_about)
            icon.setImageResource(R.drawable.outline_info_24)
            root.setOnClickListener {
                navigate(R.id.nav_about)
            }
        }

        with(binding.backupConfig) {

            setOnClickListener {
                navigate(
                    R.id.nav_backup_restore,
                    BackupRestoreFragmentArgs(true).toBundle()
                )
            }
        }

        with(binding.restoreConfig) {

            setOnClickListener {
                navigate(
                    R.id.nav_backup_restore,
                    BackupRestoreFragmentArgs(false).toBundle()
                )
            }
        }

        lifecycleScope.launch {
            loadDialogs()
        }
    }

    fun waitForService() {
        /*
         * What is waited for is the status the service writes, not the version of anything: the
         * version belongs to this app and is never zero, so waiting on it returned at once and the
         * card was filled from whatever the file happened to hold at that instant -- which is why
         * the manager could still say it was not activated right after the service had written it.
         */
        var workMode = ServiceClient.managerWorkMode
        loadEnabledIndicator(ServiceClient.serviceVersion, workMode)
        if (workMode != MANAGER_WORK_MODE_UNKNOWN) {
            return
        }

        thread {
            var count = 0

            while (ServiceClient.managerWorkMode.also { workMode = it } == MANAGER_WORK_MODE_UNKNOWN && count++ < 100) {
                Thread.sleep(100)
            }

            if (workMode != MANAGER_WORK_MODE_UNKNOWN) {
                lifecycleScope.launch {
                    loadEnabledIndicator(ServiceClient.serviceVersion, workMode)
                }
            }
        }
    }

    /**
     * The kernel's own numbers, on the line under the one that says the module is activated.
     *
     * They come from the file the service writes after asking the kernel, so what is shown here is the
     * kernel's report and not a second opinion: the registrations and the hook counts the kernel
     * holds. The service may not have written them yet, so loadKernelLine retries.
     */
    private var kernelRetries = 0

    /** Which view lifetime already observes StatusRefresh, so a second pass does not double it. */
    private var refreshObservedFor: androidx.lifecycle.LifecycleOwner? = null

    private fun loadKernelLine() {
        /*
         * A delayed retry can land after the fragment is gone. Calling binding from it threw
         * IllegalStateException once the view was destroyed, which took systemui down -- the
         * crash buffer pointed here. So: no view, no work.
         */
        if (!isAdded || view == null) return

        val kernel = ServiceClient.kernelStatus
        val loaded = kernel["state"] == "loaded"
        val inodes = kernel["apkInodes"]?.toIntOrNull() ?: 0

        /*
         * Just after boot the service is still building its tables, so kernel.json is either
         * missing or still shows the previous zero; the card then read "0" until the page was
         * reopened by hand. Retry every two seconds (at most 15 times) while that is the case.
         */
        if ((!loaded || inodes == 0) && kernelRetries < 15) {
            kernelRetries++
            // Post on the view: it cancels itself when the view is gone.
            view?.postDelayed({
                if (isAdded && view != null) loadKernelLine()
            }, 2_000L)
        } else {
            kernelRetries = 0
        }

        binding.statusCard.kernelStatus.text = when {
            !loaded -> getString(R.string.kernel_card_absent)
            else -> {
                /*
                 * The apk side is what a user can act on, so it always gets a line. The entry hooks
                 * only get one when there are any: on this device they are answered by the tiers
                 * instead, and a line reading "native 0/0 · compat 0/0" says nothing except that the
                 * reader has to know what those four numbers mean.
                 */
                val lines = mutableListOf(
                    getString(R.string.kernel_card_detail, kernel["apkInodes"], kernel["apkFailed"]),
                )

                val native = kernel["native"]
                if (native != null && native != "0/0") {
                    lines.add(
                        getString(
                            R.string.kernel_card_hooks,
                            kernel["native"]?.substringBefore("/"), kernel["native"]?.substringAfter("/"),
                            kernel["compat"]?.substringBefore("/"), kernel["compat"]?.substringAfter("/"),
                        )
                    )
                }

                lines.add(getString(R.string.kernel_card_geometry, kernel["uidTier"], kernel["setuidTier"]))
                lines.joinToString("\n")
            }
        }
    }

    fun loadEnabledIndicator(serviceVersion: Int, workMode: Int) {
        asyaApp.loadConfiguration()
        loadKernelLine()

        /*
         * The apk table is rebuilt on the service's side a moment after a package change reaches
         * it, so look a few times rather than once. Without this the card kept whatever it had
         * been opened with: uninstalling an app left the number untouched on screen while the
         * table behind it had already shrunk, which reads as "the module did not notice".
         */
        val owner = viewLifecycleOwner
        if (refreshObservedFor !== owner) {
            refreshObservedFor = owner
            StatusRefresh.generation.observe(owner) {
                if (!isAdded || view == null) return@observe
                for (step in 1..6) {
                    view?.postDelayed({ if (isAdded && view != null) loadKernelLine() }, 1_500L * step)
                }
            }
        }

        val isWorking = serviceVersion > 0 && workMode != MANAGER_WORK_MODE_UNKNOWN
        val isCrashed = workMode == MANAGER_WORK_MODE_CRASHED
        val isNoHooks = isCrashed || workMode == MANAGER_WORK_MODE_NO_HOOKS
        val isVersionMismatch = ServiceClient.serviceVersionName != BuildConfig.VERSION_NAME

        var color = when {
            !isWorking -> getColor(R.color.invalid)
            isNoHooks -> getColor(R.color.md_theme_material_amber_light_error)
            isVersionMismatch -> themeColor(android.R.attr.colorError)
            else -> themeColor(android.R.attr.colorPrimary)
        }

        with(binding.statusCard) {
            root.setCardBackgroundColor(color)

            if (isWorking) {
                if (isNoHooks) {
                    val colorError = ColorStateList.valueOf(
                        getColor(R.color.md_theme_material_amber_dark_error))

                    moduleStatus.setText(R.string.sick_mode_title)
                    moduleStatus.setTextColor(colorError)
                    setStatusIcon(R.drawable.sick_24px)
                    serviceStatus.setText(R.string.sick_mode_description)
                    serviceStatus.setTextColor(colorError)
                    filterCount.isVisible = false

                    migrateBtn.isVisible = !isCrashed
                    @Suppress("DEPRECATION")
                    migrateBtn.setOnClickListener {
                        navigate(R.id.nav_fix_issue)
                    }
                } else {
                    val image = when {
                        isVersionMismatch -> R.drawable.sentiment_worried_24px
                        workMode == MANAGER_WORK_MODE_LOADING -> R.drawable.sentiment_stressed_24px
                        else -> R.drawable.sentiment_calm_24px
                    }
                    setStatusIcon(image)

                    val versionNameSimple = if (isVersionMismatch) {
                        "${ServiceClient.serviceVersionName} - ${BuildConfig.VERSION_NAME}"
                    } else {
                        ServiceClient.serviceVersionName
                    }
                    moduleStatus.text =
                        getString(R.string.home_xposed_activated, versionNameSimple)
                    root.setOnLongClickListener {
                        ServiceClient.reloadConfigFromFile()
                        showToast(android.R.string.ok)

                        true
                    }

                    serviceStatus.text =
                        getString(R.string.home_xposed_service_on, serviceVersion)
                    filterCount.visibility = View.VISIBLE
                    filterCount.text =
                        getString(R.string.home_xposed_filter_count, ServiceClient.filterCount)
                }
            } else {
                setStatusIcon(R.drawable.sentiment_very_dissatisfied_24px)
                moduleStatus.setText(R.string.home_xposed_not_activated)
                serviceStatus.setText(R.string.home_xposed_service_off)
                filterCount.isVisible = false
            }

            TextViewCompat.setCompoundDrawableTintList(
                moduleStatus, moduleStatus.textColors)
        }

        val isHooks = !isNoHooks && isWorking

        binding.manageApps.root.isVisible = isHooks
        binding.manageTemplates.root.isVisible = isHooks
        binding.navLogs.root.isVisible = isWorking
        binding.navSettings.root.isVisible = isWorking
        (binding.backupConfig.parent as ViewGroup).isVisible = isHooks
    }

    private fun setStatusIcon(@DrawableRes res: Int) {
        binding.statusCard.moduleStatus
            .setCompoundDrawablesRelativeWithIntrinsicBounds(res, 0, 0, 0)
    }

    private fun loadDialogs() {
        if (PrefManager.enableInternet == ENABLE_INTERNET_UNKNOWN) {
            loadEnableInternetDialog()
        }
    }

    private fun loadEnableInternetDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setCancelable(false)
            .setTitle(R.string.settings_enable_internet)
            .setMessage(R.string.settings_enable_internet_summary)
            .setPositiveButton(R.string.yes) { _, _ ->
                PrefManager.enableInternet = ENABLE_INTERNET_ON
            }
            .setNegativeButton(R.string.no) { _, _ ->
                PrefManager.enableInternet = ENABLE_INTERNET_OFF
            }
            .show()
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_info -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/Grachevskaya/asya/wiki/About-HMA%E2%80%90OSS".toUri()
                })
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance() = HomeFragment()
    }
}
