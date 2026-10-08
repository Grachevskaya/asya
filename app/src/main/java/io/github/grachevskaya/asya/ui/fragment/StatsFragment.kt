package io.github.grachevskaya.asya.ui.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.databinding.FragmentStatsBinding
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.adapter.StatAdapter
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showToast
import io.github.grachevskaya.asya.util.PackageHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StatsFragment : Fragment(R.layout.fragment_stats) {
    private val page get() = parentFragment as TabbedLogsFragment
    private val loadingIndicator get() = page.sharedLoadingIndicator
    private val toolbar get() = page.sharedToolbar

    private val binding by viewBinding(FragmentStatsBinding::bind)
    private val adapter by lazy {
        StatAdapter {
            lifecycleScope.launch {
                PackageHelper.isRefreshing
                    .flowWithLifecycle(lifecycle)
                    .collect { isRefreshing ->
                        if (!isRefreshing && it.wasRefreshing) {
                            it.wasRefreshing = false

                            updateLogs()
                        }
                    }
            }
        }
    }
    private var statCache: String? = null

    /** One more pass once the package cache has answered, so names and icons are the real ones. */
    private var reboundAfterCache = false

    private fun updateLogs() {
        /*
         * The spinner belongs to this page's own load, and it goes off when that load is answered.
         *
         * The shared package-cache flag reads as true before that cache has ever reported anything,
         * and nothing on this page turns it off, so a page waiting on it would sit behind a spinner
         * for good, however quickly the statistics themselves had arrived.
         */
        loadingIndicator.isVisible = true

        lifecycleScope.launch {
            statCache = runCatching { ServiceClient.detailedFilterStats }.getOrNull()

            /*
             * A row is a name and an icon, and both come out of the package cache: binding before it
             * has been built left a list of package names with nothing beside them. The wait ends
             * either way -- the statistics are what this page is about, and they are shown whether or
             * not the cache answers.
             */
            if (statCache != null) {
                withTimeoutOrNull(6_000L) { PackageHelper.appList.first { it.isNotEmpty() } }
            }

            loadingIndicator.isVisible = false
            binding.swipeRefresh.isRefreshing = false

            val stats = statCache?.let { runCatching { FilterHolder.parse(it) }.getOrNull() }

            if (stats == null) {
                binding.serviceOff.isVisible = true
                binding.summaryCard.isVisible = false
                binding.empty.isVisible = false
                return@launch
            }

            binding.serviceOff.isVisible = false

            val pkgMgrTotal = stats.filterCounts.values.sumOf { it.packageManagerCount }
            val actLaunchTotal = stats.filterCounts.values.sumOf { it.activityLaunchCount }

            binding.summaryCard.isVisible = true
            binding.summaryTotal.text = (pkgMgrTotal + actLaunchTotal).toString()
            binding.totalPkgMgr.text = pkgMgrTotal.toString()
            binding.totalActLaunch.text = actLaunchTotal.toString()
            binding.empty.isVisible = stats.filterCounts.isEmpty()

            val countsKeys =
                stats.filterCounts.keys
                    .sortedBy { stats.filterCounts[it]!!.totalCount }
                    .asReversed()

            for (key in countsKeys) {
                adapter.addOrUpdateEntry(
                    key,
                    stats.filterCounts[key]!!,
                )
            }

            adapter.clearEntriesIfNotFound(countsKeys)

            if (!reboundAfterCache) {
                reboundAfterCache = true

                lifecycleScope.launch {
                    PackageHelper.isRefreshing.first { !it }
                    updateLogs()
                }
            }
        }
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_delete -> {
                ServiceClient.clearFilterStats()
                showToast(android.R.string.ok)
                updateLogs()
            }

            R.id.menu_info -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setIcon(R.drawable.outline_info_24)
                    .setTitle(R.string.logs_categories_title)
                    .setMessage(
                        getString(R.string.logs_categories_pkg_mgr) + "\n" +
                            getString(R.string.logs_categories_act_launch),
                    ).setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { updateLogs() }

        updateLogs()
    }

    override fun onResume() {
        super.onResume()

        setupToolbar(
            toolbar,
            title = getString(R.string.title_filter_logs),
            menuRes = R.menu.menu_stats,
            onMenuOptionSelected = this::onMenuOptionSelected,
        )
        toolbar.title = null
    }
}
