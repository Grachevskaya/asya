package io.github.grachevskaya.asya.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showToast
import io.github.grachevskaya.asya.util.PackageHelper
import kotlinx.coroutines.launch
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.FragmentLogsBinding
import io.github.grachevskaya.asya.ui.adapter.StatAdapter

class StatsFragment : Fragment(R.layout.fragment_logs) {
    private val page get() = parentFragment as TabbedLogsFragment
    private val loadingIndicator get() = page.sharedLoadingIndicator
    private val toolbar get() = page.sharedToolbar

    private val binding by viewBinding(FragmentLogsBinding::bind)
    private val adapter by lazy { StatAdapter {
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
    } }
    private var statCache: String? = null

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
            loadingIndicator.isVisible = false

            if (statCache == null) {
                binding.serviceOff.visibility = View.VISIBLE
            } else {
                binding.serviceOff.visibility = View.GONE

                val stats = FilterHolder.parse(statCache!!)

                fun getTotalCount(key: String) = stats.filterCounts[key]!!.totalCount

                val countsKeys = stats.filterCounts.keys.sortedWith { key1, key2 ->
                    getTotalCount(key1).compareTo(getTotalCount(key2))
                }.asReversed()

                for (key in countsKeys) {
                    adapter.addOrUpdateEntry(
                        key,
                        stats.filterCounts[key]!!
                    )
                }

                adapter.clearEntriesIfNotFound(countsKeys)
            }
        }
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_refresh -> updateLogs()
            R.id.menu_delete -> {
                ServiceClient.clearFilterStats()
                showToast(android.R.string.ok)
                updateLogs()
            }
            R.id.menu_info -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/Grachevskaya/asya/wiki/About-HMA%E2%80%90OSS#filter-logs-categories".toUri()
                })
            }
            // TODO: Add other options when required
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        binding.list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

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
    }
}
