package io.github.grachevskaya.asya.ui.fragment

import android.animation.ValueAnimator
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.clearFragmentResultListener
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.FragmentTemplateSettingsBinding
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.ui.util.navController
import io.github.grachevskaya.asya.ui.util.navigate
import io.github.grachevskaya.asya.ui.util.registerOnBackCallback
import io.github.grachevskaya.asya.ui.util.styleSegmentedCards
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.viewmodel.TemplateSettingsViewModel
import kotlinx.coroutines.launch

/** The size the name is read at, and the size it is written at: the one growth of this page. */
private const val NAME_READING = 22f
private const val NAME_WRITING = 32f

class TemplateSettingsFragment : Fragment(R.layout.fragment_template_settings) {
    private val binding by viewBinding(FragmentTemplateSettingsBinding::bind)
    private val viewModel by viewModels<TemplateSettingsViewModel> {
        val args by navArgs<TemplateSettingsFragmentArgs>()
        TemplateSettingsViewModel.Factory(args)
    }

    private fun onBack(delete: Boolean) {
        viewModel.name = viewModel.name?.trim()
        val nameTaken =
            viewModel.name != viewModel.originalName &&
                (ConfigManager.hasTemplate(viewModel.name) || viewModel.name == null)
        if (nameTaken || delete) {
            val builder =
                MaterialAlertDialogBuilder(
                    requireContext(),
                    R.style.ThemeOverlay_MaterialAlertDialog_Centered_FullWidthButtons,
                )
                    .setIcon(if (delete) R.drawable.outline_delete_24 else R.drawable.outline_info_24)
                    .setTitle(if (delete) R.string.template_delete_title else R.string.template_name_invalid)
                    .setMessage(if (delete) R.string.template_delete else R.string.template_name_already_exist)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        saveResult(delete)
                    }
            if (delete) builder.setNegativeButton(android.R.string.cancel, null)
            builder.show()
        } else {
            saveResult(false)
        }
    }

    /*
     * Touching the name makes it the largest thing on the page: the line grows into the size it is
     * written at, the rule under it draws in from its start, and the pencil steps aside, all for the
     * same length of time. Leaving the name takes the three of them back together.
     */
    private fun swapNameSizes(writing: Boolean) {
        val duration = 220L
        val easing = FastOutSlowInInterpolator()

        // The size is animated in sp rather than through an ObjectAnimator: its getter reads the
        // size in pixels, so a first frame taken from it would set the name to that many sp.
        val from = binding.templateName.textSize / resources.displayMetrics.scaledDensity
        ValueAnimator.ofFloat(from, if (writing) NAME_WRITING else NAME_READING).apply {
            this.duration = duration
            interpolator = easing
            addUpdateListener { binding.templateName.setTextSize(TypedValue.COMPLEX_UNIT_SP, it.animatedValue as Float) }
            start()
        }

        binding.templateNameRule
            .animate()
            .scaleX(if (writing) 1f else 0f)
            .alpha(if (writing) 1f else 0f)
            .setDuration(duration)
            .setInterpolator(easing)
            .start()

        binding.templateNameEdit
            .animate()
            .alpha(if (writing) 0f else 1f)
            .setDuration(duration)
            .setInterpolator(easing)
            .start()

        binding.templateName.isCursorVisible = writing
    }

    private fun focusName() {
        binding.templateName.requestFocus()
        binding.templateName.setSelection(binding.templateName.text?.length ?: 0)
        val ime = requireContext().getSystemService(InputMethodManager::class.java)
        ime?.showSoftInput(binding.templateName, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val ime = requireContext().getSystemService(InputMethodManager::class.java)
        ime?.hideSoftInputFromWindow(binding.templateName.windowToken, 0)
        binding.templateName.clearFocus()
    }

    /*
     * The name is a title until it is touched and a field once it is, and the transform carries the
     * one surface into the other: same place, same size growing, the color of the page turning into
     * the color of the field on the way. Two views take turns, but the page reads as one thing
     * changing its mind about being read.
     */
    private fun saveResult(delete: Boolean) {
        setFragmentResult(
            "template_settings",
            Bundle().apply {
                putString("name", if (delete) null else viewModel.name)
                putStringArrayList("appliedList", viewModel.appliedAppList.value)
                putStringArrayList("targetList", viewModel.targetAppList.value)
            },
        )
        navController.navigateUp()
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        registerOnBackCallback { onBack(false) }

        setupToolbar(
            toolbar = binding.toolbar,
            title = getString(R.string.title_template_settings),
            navigationIcon = R.drawable.baseline_arrow_back_24,
            navigationOnClick = { onBack(false) },
            menuRes = R.menu.menu_delete,
            onMenuOptionSelected = {
                onBack(true)
            },
        )

        binding.templateName.setText(viewModel.name)
        binding.workMode.setText(if (viewModel.isWhiteList) R.string.whitelist else R.string.blacklist)
        binding.templateName.setOnFocusChangeListener { _, focused -> swapNameSizes(focused) }
        binding.templateName.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        binding.templateName.addTextChangedListener { viewModel.name = it.toString() }

        // Anywhere on the name, and the pencil, put the caret in the line.
        binding.nameArea.setOnClickListener { focusName() }
        binding.templateNameEdit.setOnClickListener { focusName() }

        // Touching the page away from the name gives the sizes back.
        binding.templateContent.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) activity?.currentFocus?.clearFocus()
            false
        }

        swapNameSizes(false)

        // The rows of the page are one group, the way the home and about screens group theirs.
        binding.rows.styleSegmentedCards(requireContext())
        binding.targetApps.setOnClickListener {
            setFragmentResultListener("app_select") { _, bundle ->
                viewModel.targetAppList.value = bundle.getStringArrayList("checked")!!
                clearFragmentResultListener("app_select")
            }
            val args =
                ScopeFragmentArgs(
                    filterOnlyEnabled = false,
                    checked = viewModel.targetAppList.value.toTypedArray(),
                    hideMyself = false,
                )
            navigate(R.id.nav_scope, args.toBundle())
        }
        binding.appliedApps.setOnClickListener {
            setFragmentResultListener("app_select") { _, bundle ->
                viewModel.appliedAppList.value = bundle.getStringArrayList("checked")!!
                clearFragmentResultListener("app_select")
            }
            val args =
                ScopeFragmentArgs(
                    filterOnlyEnabled = true,
                    isWhiteList = viewModel.isWhiteList,
                    checked = viewModel.appliedAppList.value.toTypedArray(),
                )
            navigate(R.id.nav_scope, args.toBundle())
        }

        lifecycleScope.launch {
            viewModel.targetAppList.collect {
                val fmt =
                    if (viewModel.isWhiteList) {
                        R.string.template_apps_visible_count
                    } else {
                        R.string.template_apps_invisible_count
                    }
                binding.targetApps.text = String.format(getString(fmt), it.size)
            }
        }
        lifecycleScope.launch {
            viewModel.appliedAppList.collect {
                binding.appliedApps.text = String.format(getString(R.string.template_applied_count), it.size)
            }
        }

        setEdge2EdgeFlags(binding.root)
    }
}
