package io.github.grachevskaya.asya.ui.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.data.AppConstants
import io.github.grachevskaya.asya.databinding.FragmentAboutBinding
import io.github.grachevskaya.asya.databinding.FragmentAboutListItemBinding
import io.github.grachevskaya.asya.ui.util.AccessibilityUtils
import io.github.grachevskaya.asya.ui.util.Reveal
import io.github.grachevskaya.asya.ui.util.ThemeUtils.attrDrawable
import io.github.grachevskaya.asya.ui.util.ThemeUtils.cornerSize
import io.github.grachevskaya.asya.ui.util.ThemeUtils.homeItemBackgroundColor
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.styleSegmentedCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
class AboutFragment : Fragment(R.layout.fragment_about) {
    private val binding by viewBinding(FragmentAboutBinding::bind)

    /** The three moves that open the credits are written once, in the controller. */
    private var reveal: Reveal? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        setEdge2EdgeFlags(binding.root, bottom = 0) { _, _, _, bottom ->
            binding.bottomPadding.minimumHeight = bottom
        }

        val tint = ColorStateList.valueOf(homeItemBackgroundColor())

        if (!AccessibilityUtils.isAnimationEnabled(requireContext().contentResolver)) {
            with(binding.aboutScroll) {
                overScrollMode = View.OVER_SCROLL_NEVER
            }
        }

        // The bar the other sub pages stand under: it carries the title and the way back.
        with(binding.pageBar.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.title_about),
                navigationIcon = R.drawable.baseline_arrow_back_24,
                navigationOnClick = { findNavController().navigateUp() },
            )
            title = null
        }
        binding.pageBar.collapsing.title = getString(R.string.title_about)

        reveal =
            Reveal(
                chevron = binding.expandDevs,
                folded = binding.listDeveloper,
                widthOf = { binding.listPeople.width },
                onClosed = { binding.listPeople.styleSegmentedCards(requireContext()) },
            )

        with(binding.aboutHeader) {
            appIcon.setImageResource(AppConstants.appIcon)

            appName.setText(R.string.app_name)
            appVersion.text = BuildConfig.APP_VERSION_NAME

            setOnClickUrl(linkGithub, "https://github.com/Grachevskaya/asya")

            appInfoTop.backgroundTintList = tint
            (appName.parent as View).backgroundTintList = tint
        }

        with(binding.aboutDescription) {
            contentTitle.setText(R.string.title_about)
            contentDescription.setText(R.string.about_description)
        }

        // The lists are card groups now -- the same code the home screen styles its rows with,
        // and the description is the card recipe from view_half_card, so the surfaces here come
        // from the theme instead of from a shape drawn in this file.

        // The header comes from the shape system: it was three xml shapes with 24dp written into
        // them, and the tokens are where Material 3 expressive keeps the corners its own surfaces
        // are rounded with. The tone is the one the home cards use.
        val panel =
            cornerSize(
                com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge,
                com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraLarge,
                24,
            )
        val panelFill =
            android.content.res.ColorStateList
                .valueOf(requireContext().homeItemBackgroundColor())

        binding.aboutHeader.appInfoTop.background =
            MaterialShapeDrawable(
                ShapeAppearanceModel
                    .builder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, panel)
                    .setTopRightCorner(CornerFamily.ROUNDED, panel)
                    .build(),
            ).apply { fillColor = panelFill }

        binding.aboutHeader.aboutHeaderPanel.background =
            MaterialShapeDrawable(
                ShapeAppearanceModel
                    .builder()
                    .setBottomLeftCorner(CornerFamily.ROUNDED, panel)
                    .setBottomRightCorner(CornerFamily.ROUNDED, panel)
                    .build(),
            ).apply { fillColor = panelFill }

        with(binding.devHeader) {
            background =
                LayerDrawable(
                    arrayOf(
                        MaterialShapeDrawable(
                            ShapeAppearanceModel
                                .builder()
                                .setAllCorners(
                                    CornerFamily.ROUNDED,
                                    cornerSize(
                                        com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge,
                                        com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraLarge,
                                        24,
                                    ),
                                ).build(),
                        ).apply {
                            fillColor =
                                android.content.res.ColorStateList
                                    .valueOf(android.graphics.Color.TRANSPARENT)
                        },
                        attrDrawable(android.R.attr.selectableItemBackground),
                    ),
                )
            clipToOutline = true

            // The credits are folded away: the two halves are listed side by side, and neither is in
            // the way of what the page is for.
            setOnClickListener {
                reveal?.toggle()

                // The visible rows decide where the group’s large corners go, and the group is the
                // whole list: the two credited halves and the folded one are one surface. This has
                // to come after the folded half has been shown or hidden, or the corners are the
                // ones the group wore a moment ago.
                binding.listPeople.styleSegmentedCards(requireContext())
            }
        }

        // One entry for one person: both halves are his, and a subtitle can say so.
        with(binding.listAsya) {
            addDevItem(this, R.drawable.cont_kodatemitsuru, "KodateMitsuru", "Asya, Tosya Developer", "https://github.com/KodateMitsuru")
        }

        // The rest of the kernel half, with the pictures shipped beside the rest of them.
        with(binding.listTosya) {
            addDevItem(this, R.drawable.cont_anatdx, "Anatdx", "Tosya Contributor", "https://github.com/Anatdx")
            addDevItem(this, R.drawable.cont_n1k0la1, "N1-k0-la1", "Tosya Contributor", "https://github.com/N1-k0-la1")
        }

        // What the pair stands on. Folded away, and both halves of it restored: the fork this comes
        // from, and the closed project that fork itself came from.
        with(binding.listHmaOss) {
            addDevItem(this, R.drawable.cont_fk, "frknkrc44", "HMA-OSS, upstream", "https://github.com/frknkrc44")
            addDevItem(this, R.drawable.cont_oukaromf, "OukaroMF", "HMA-OSS Alt Icon Designer", "https://github.com/OukaroMF")
            addDevItem(this, R.drawable.cont_chunqiu, "longze", "HMA-OSS Contributor", null)
        }

        with(binding.listHma) {
            addDevItem(this, R.drawable.cont_nullptr, "nullptr", "HMA Developer", "https://github.com/Dr-TSNG")
            addDevItem(this, R.drawable.cont_k, "Ketal", "HMA Collaborator", "https://github.com/keta1")
            addDevItem(this, R.drawable.cont_aviraxp, "aviraxp", "HMA Collaborator", "https://github.com/aviraxp")
            addDevItem(this, R.drawable.cont_icon_designer, "辉少菌", "HMA Icon Designer", "http://www.coolapk.com/u/1560270")
            addDevItem(this, R.drawable.cont_cpp_master, "LoveSy", "HMA Idea Provider", "https://github.com/yujincheng08")
        }

        // Original HMA devs
        with(binding.listOpenSource) {
            backgroundTintList = tint
            clipToOutline = true

            addLibraryItem(this, "ZygoteLoader (fork)", "MIT License", "https://github.com/aerath-stuff/ZygoteLoader")
            addLibraryItem(this, "AndroidVMTools (fork)", "MIT License", "https://github.com/aerath-stuff/AndroidVMTools")
            addLibraryItem(this, "PanamaPort (fork)", "MIT License", "https://github.com/aerath-stuff/PanamaPort")
            addLibraryItem(this, "Coil", "Apache-2.0 License", "https://github.com/coil-kt/coil")
        }
    }

    fun addDevItem(
        layout: LinearLayout,
        @DrawableRes avatarResId: Int,
        name: String,
        desc: String,
        url: String?,
    ) {
        val newLayout = FragmentAboutListItemBinding.inflate(layoutInflater)
        setOnClickUrl(newLayout.root, url)

        if (avatarResId != 0) {
            val icon = newLayout.aboutPersonIcon
            val context = requireContext()
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap =
                    withContext(Dispatchers.IO) {
                        BitmapFactory.decodeResource(context.resources, avatarResId)
                    }
                icon.setImageDrawable(
                    RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
                        isCircular = true
                    },
                )
            }
        } else {
            newLayout.aboutPersonIcon.isVisible = false
        }

        newLayout.text1.text = name
        newLayout.text2.text = desc
        layout.addView(newLayout.root)

        // One person joins the one group the credited halves share, so that group is styled again.
        (layout.parent as? ViewGroup)?.styleSegmentedCards(requireContext())
    }

    fun addLibraryItem(
        layout: LinearLayout,
        name: String,
        desc: String,
        url: String,
    ) {
        val newLayout = FragmentAboutListItemBinding.inflate(layoutInflater)
        setOnClickUrl(newLayout.root, url)

        newLayout.aboutPersonIcon.isVisible = false
        newLayout.text1.text = name
        newLayout.text2.text = desc
        layout.addView(newLayout.root)

        layout.styleSegmentedCards(requireContext())
    }

    fun setOnClickUrl(
        view: View,
        url: String?,
    ) {
        if (url == null) return

        view.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = url.toUri()
            startActivity(intent)
        }
    }
}
