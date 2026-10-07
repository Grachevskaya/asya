package io.github.grachevskaya.asya.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.FragmentTabbedLogsBinding
import io.github.grachevskaya.asya.ui.fragment.LogsFragment
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags

class TabbedLogsFragment : Fragment() {
    private val binding by viewBinding(FragmentTabbedLogsBinding::bind)

    /*
     * The two pages under the tabs share this page's bar and its indicator, and they are built by the
     * framework when the state is restored -- so they take no view of their own and ask here instead.
     */
    internal val sharedToolbar get() = binding.toolbar
    internal val sharedLoadingIndicator get() = binding.loadingIndicator

    private val tabsList by lazy {
        listOf(
            getString(R.string.title_logs),
            getString(R.string.title_filter_logs),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tabbed_logs, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.collapsing.title = getString(R.string.title_logs)
        binding.toolbar.title = null

        with(binding.viewPager) {
            adapter =
                object : FragmentStateAdapter(childFragmentManager, lifecycle) {
                    override fun createFragment(index: Int): Fragment =
                        when (index) {
                            0 -> LogsFragment()
                            1 -> StatsFragment()
                            else -> throw UnsupportedOperationException("Invalid tab index: $index")
                        }

                    override fun getItemCount() = tabsList.size
                }
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabsList[position]
        }.attach()

        setEdge2EdgeFlags(binding.root)
    }
}
