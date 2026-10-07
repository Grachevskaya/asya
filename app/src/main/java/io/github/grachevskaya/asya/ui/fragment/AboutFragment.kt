package io.github.grachevskaya.asya.ui.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.request.crossfade
import coil3.request.placeholder
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.data.AppConstants
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.ui.util.AccessibilityUtils
import io.github.grachevskaya.asya.ui.util.ThemeUtils.attrDrawable
import io.github.grachevskaya.asya.ui.util.ThemeUtils.homeItemBackgroundColor
import io.github.grachevskaya.asya.ui.util.dpToPx
import io.github.grachevskaya.asya.ui.util.navController
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.databinding.FragmentAboutBinding
import io.github.grachevskaya.asya.databinding.FragmentAboutListItemBinding
import org.json.JSONObject

@Suppress("DEPRECATION")
class AboutFragment : Fragment(R.layout.fragment_about) {
    private val binding by viewBinding(FragmentAboutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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

        with(binding.aboutHeader) {
            with(backButton.parent as View) {
                setOnClickListener { navController.navigateUp()  }
                backgroundTintList = tint
            }

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
            contentDescription.backgroundTintList = tint
        }

        with(binding.listPeople) {
            backgroundTintList = tint
            clipToOutline = true
        }

        with(binding.devHeader) {
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    setColor(0)
                    cornerRadius = 24.dpToPx
                },
                attrDrawable(android.R.attr.selectableItemBackground),
            ))
            clipToOutline = true

            // The credits are folded away: the two halves are listed side by side, and neither is in
            // the way of what the page is for.
            setOnClickListener {
                TransitionManager.beginDelayedTransition(binding.listDeveloper, AutoTransition())
                binding.expandDevs.animate()
                    .rotation(if (binding.listDeveloper.isVisible) 0.0f else 180.0f)
                    .start()
                binding.listDeveloper.isVisible = !binding.listDeveloper.isVisible
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
            addDevItem(this, R.drawable.cont_cpp_master,  "LoveSy", "HMA Idea Provider", "https://github.com/yujincheng08")
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

    fun addDevItem(layout: LinearLayout, @DrawableRes avatarResId: Int, name: String, desc: String, url: String?) {
        val newLayout = FragmentAboutListItemBinding.inflate(layoutInflater)
        setOnClickUrl(newLayout.root, url)

        if (avatarResId != 0) {
            val icon = newLayout.aboutPersonIcon
            val context = requireContext()
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeResource(context.resources, avatarResId)
                }
                icon.setImageDrawable(
                    RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
                        isCircular = true
                    }
                )
            }
        } else {
            newLayout.aboutPersonIcon.isVisible = false
        }

        newLayout.text1.text = name
        newLayout.text2.text = desc
        layout.addView(newLayout.root)
    }

    fun addLibraryItem(layout: LinearLayout, name: String, desc: String, url: String) {
        val newLayout = FragmentAboutListItemBinding.inflate(layoutInflater)
        setOnClickUrl(newLayout.root, url)

        newLayout.aboutPersonIcon.isVisible = false
        newLayout.text1.text = name
        newLayout.text2.text = desc
        layout.addView(newLayout.root)
    }

    fun setOnClickUrl(view: View, url: String?) {
        if (url == null) return

        view.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = url.toUri()
            startActivity(intent)
        }
    }
}
