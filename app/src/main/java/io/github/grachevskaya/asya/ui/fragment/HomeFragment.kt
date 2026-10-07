package io.github.grachevskaya.asya.ui.fragment

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock.elapsedRealtime
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.github.grachevskaya.asya.ui.util.styleSegmentedCards
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.BuildConfig
import io.github.grachevskaya.asya.MyApp.Companion.asyaApp
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_CRASHED
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_LOADING
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_NO_HOOKS
import io.github.grachevskaya.asya.common.Constants.MANAGER_WORK_MODE_UNKNOWN
import io.github.grachevskaya.asya.databinding.FragmentHomeBinding
import io.github.grachevskaya.asya.databinding.ViewHalfCardBinding
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.StatusRefresh
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor
import io.github.grachevskaya.asya.ui.util.ThemeUtils.cornerSize
import io.github.grachevskaya.asya.ui.util.dpToPx
import io.github.grachevskaya.asya.ui.util.navigate
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showToast
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val binding by viewBinding(FragmentHomeBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        with(binding.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.app_name),
            )
            title = null

            setOnLongClickListener {
                val dialog =
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.app_name)
                        .create()

                dialog.setView(
                    Chronometer(context).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -2)
                        base = elapsedRealtime() + 3000
                        textSize = 24.dpToPx
                        gravity = Gravity.CENTER
                        typeface = Typeface.SERIF
                        onChronometerTickListener = {
                            if (elapsedRealtime() >= base) {
                                stop()
                                dialog.dismiss()

                                isTheFinalCountDown
                            }
                        }
                        isCountDown = true
                        start()
                    },
                )

                dialog.show()

                true
            }
        }

        setEdge2EdgeFlags(binding.root)
    }

    override fun onStart() {
        super.onStart()

        waitForService()

        styleSegmentedRows(
            listOf(
                binding.hooksCard.rowInode.root,
                binding.hooksCard.rowFindUser.root,
                binding.hooksCard.rowCred.root,
            ),
        )
        styleSegmentedRows(
            listOf(
                binding.injectionCard.rowVersion.root,
                binding.injectionCard.rowService.root,
                binding.injectionCard.rowFilters.root,
            ),
        )

        with(binding.howToUse) {
            text1.text = getString(R.string.home_faq_title)
            icon.setImageResource(R.drawable.baseline_help_outline_24)
            icon.themedTint(android.R.attr.colorPrimary)
            root.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setIcon(R.drawable.outline_info_24)
                    .setTitle(R.string.home_faq_title)
                    .setMessage(
                        getString(R.string.home_faq_create) +
                            "\n\n" +
                            getString(R.string.home_faq_apply) +
                            "\n\n" +
                            getString(R.string.home_faq_mode) +
                            "\n\n" +
                            getString(R.string.home_faq_settings) +
                            "\n\n" +
                            getString(R.string.home_faq_effect),
                    ).setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }

    }

    private fun styleSegmentedRows(rows: List<MaterialCardView>) = rows.styleSegmentedCards(requireContext())

    fun waitForService() {
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

    private var kernelRetries = 0

    private var refreshObservedFor: androidx.lifecycle.LifecycleOwner? = null

    private fun ImageView.themedTint(
        @AttrRes attr: Int,
    ) {
        imageTintList = ColorStateList.valueOf(themeColor(attr))
    }

    private fun ViewHalfCardBinding.setState(loaded: Boolean) {
        cardState.text =
            getString(if (loaded) R.string.home_card_loaded else R.string.home_card_not_loaded)
        // The chip carries its own surface and label, instead of a shape drawn beside it.
        cardState.chipBackgroundColor =
            ColorStateList.valueOf(
                themeColor(
                    if (loaded) {
                        androidx.appcompat.R.attr.colorPrimary
                    } else {
                        androidx.appcompat.R.attr.colorError
                    },
                ),
            )
        cardState.setTextColor(
            themeColor(
                if (loaded) {
                    com.google.android.material.R.attr.colorOnPrimary
                } else {
                    com.google.android.material.R.attr.colorOnError
                },
            ),
        )
    }

    private fun loadKernelLine() {
        if (!isAdded || view == null) return

        val kernel = ServiceClient.kernelStatus
        val loaded = kernel["state"] == "loaded"
        val inodes = kernel["apkInodes"]?.toIntOrNull() ?: 0

        if ((!loaded || inodes == 0) && kernelRetries < 15) {
            kernelRetries++
            view?.postDelayed({
                if (isAdded && view != null) loadKernelLine()
            }, 2_000L)
        } else {
            kernelRetries = 0
        }

        binding.kernelCard.setState(loaded)

        with(binding.hooksCard) {
            rowInode.itemTitle.text = getString(R.string.home_hook_inode)
            rowInode.itemIcon.setImageResource(R.drawable.baseline_assignment_24)
            rowInode.itemIcon.themedTint(android.R.attr.colorPrimary)
            rowInode.itemSubtitle.text =
                if (loaded) {
                    getString(R.string.kernel_card_detail, kernel["apkInodes"], kernel["apkFailed"])
                } else {
                    getString(R.string.kernel_card_absent)
                }

            rowFindUser.itemTitle.text = getString(R.string.home_hook_find_user)
            rowFindUser.itemIcon.setImageResource(R.drawable.baseline_my_location_24)
            rowFindUser.itemIcon.themedTint(android.R.attr.colorPrimary)
            rowFindUser.itemSubtitle.text =
                kernel["uidTier"] ?: getString(R.string.home_card_not_loaded)

            rowCred.itemTitle.text = getString(R.string.home_hook_cred)
            rowCred.itemIcon.setImageResource(R.drawable.add_moderator_24px)
            rowCred.itemIcon.themedTint(android.R.attr.colorPrimary)
            rowCred.itemSubtitle.text =
                kernel["setuidTier"] ?: getString(R.string.home_card_not_loaded)
        }
    }

    fun loadEnabledIndicator(
        serviceVersion: Int,
        workMode: Int,
    ) {
        asyaApp.loadConfiguration()
        loadKernelLine()

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

        binding.zygoteCard.cardIcon.setImageResource(R.drawable.ic_outline_layers_24)
        binding.kernelCard.cardIcon.setImageResource(R.drawable.outline_android_24)
        binding.zygoteCard.cardIcon.themedTint(
            com.google.android.material.R.attr.colorOnSecondaryContainer,
        )
        binding.kernelCard.cardIcon.themedTint(
            com.google.android.material.R.attr.colorOnSecondaryContainer,
        )
        binding.zygoteCard.cardTitle.text = getString(R.string.home_card_zygote)
        binding.kernelCard.cardTitle.text = getString(R.string.home_card_kernel)
        binding.zygoteCard.cardTitle.setTextColor(
            themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
        )
        binding.kernelCard.cardTitle.setTextColor(
            themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
        )
        binding.zygoteCard.setState(isWorking)

        with(binding.injectionCard) {
            rowVersion.itemTitle.text = getString(R.string.home_row_manager)
            rowVersion.itemIcon.setImageResource(R.drawable.outline_info_24)
            rowVersion.itemIcon.themedTint(android.R.attr.colorPrimary)
            rowVersion.itemSubtitle.text =
                if (isVersionMismatch) {
                    "${BuildConfig.VERSION_NAME} · ${ServiceClient.serviceVersionName}"
                } else {
                    BuildConfig.VERSION_NAME
                }

            rowService.itemTitle.text = getString(R.string.home_row_service)
            rowService.itemIcon.setImageResource(R.drawable.plug_connect_24px)
            rowService.itemIcon.themedTint(android.R.attr.colorPrimary)
            rowService.itemSubtitle.text =
                if (isWorking) {
                    getString(R.string.home_xposed_service_on, serviceVersion)
                } else {
                    getString(R.string.home_xposed_service_off)
                }

            rowFilters.itemTitle.text = getString(R.string.home_row_filters)
            rowFilters.itemIcon.setImageResource(R.drawable.outline_shield_24)
            rowFilters.itemIcon.themedTint(android.R.attr.colorPrimary)
            rowFilters.itemSubtitle.text =
                if (isWorking && !isNoHooks) {
                    ServiceClient.filterCount.toString()
                } else {
                    getString(R.string.home_figures_none)
                }

            root.setOnLongClickListener {
                ServiceClient.reloadConfigFromFile()
                showToast(android.R.string.ok)

                true
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance() = HomeFragment()
    }
}
