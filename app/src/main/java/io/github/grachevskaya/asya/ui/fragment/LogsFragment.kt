package io.github.grachevskaya.asya.ui.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.MyApp
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.OSUtils
import io.github.grachevskaya.asya.databinding.FragmentLogsBinding
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.adapter.LogAdapter
import io.github.grachevskaya.asya.ui.util.contentResolver
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showToast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment(R.layout.fragment_logs) {
    private val binding by viewBinding(FragmentLogsBinding::bind)

    private val page get() = parentFragment as TabbedLogsFragment
    private val loadingIndicator get() = page.sharedLoadingIndicator
    private val toolbar get() = page.sharedToolbar
    private val adapter by lazy { LogAdapter(requireContext()) }
    private var logCache: String? = null

    private val saveSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-log")) save@{ uri ->
            if (uri == null) return@save
            if (logCache.isNullOrEmpty()) {
                showToast(R.string.logs_empty)
                return@save
            }
            contentResolver.openOutputStream(uri).use { output ->
                if (output == null) {
                    showToast(R.string.home_export_failed)
                } else {
                    output.write(
                        OSUtils
                            .collectOSInfo(
                                requireContext(),
                                ServiceClient.serviceVersionName,
                            ).toByteArray(),
                    )
                    output.write("\n\n".toByteArray())
                    output.write(logCache!!.toByteArray())
                }
            }
            showToast(R.string.logs_saved)
        }

    private fun updateLogs() {
        binding.serviceOff.isVisible = ServiceClient.serviceVersion <= 0

        if (binding.serviceOff.isVisible) return

        loadingIndicator.isVisible = true

        MyApp.asyaApp.globalScope.launch {
            try {
                logCache =
                    try {
                        ServiceClient.logs
                    } catch (_: Throwable) {
                        "[ERROR] <01-01 01:01:01> (${getString(
                            R.string.app_name,
                        )}) Cannot read logs due to Binder issues, try reading ${ServiceClient.logFileLocation} manually"
                    }

                val raw = logCache?.split("\n")
                if (raw != null) {
                    val logList =
                        buildList {
                            val cur = StringBuilder()
                            for (line in raw) {
                                if (line.startsWith('[')) {
                                    if (cur.isNotEmpty()) {
                                        val log = LogAdapter.parseLog(cur.toString())
                                        if (log != null) add(log)
                                    }
                                    cur.clear()
                                }
                                if (cur.isNotEmpty()) cur.append('\n')
                                cur.append(line)
                            }
                            if (cur.isNotEmpty()) {
                                val log = LogAdapter.parseLog(cur.toString())
                                if (log != null) add(log)
                            }
                            if (!PrefManager.logFilter_reverseOrder) reverse()
                        }

                    lifecycleScope.launch {
                        loadingIndicator.isVisible = false
                        binding.swipeRefresh.isRefreshing = false
                        adapter.logs = logList
                    }
                }
            } catch (cause: Throwable) {
              /*
               * Whatever went wrong in here -- a parse, a preference, anything -- the indicator has
               * to come down. Leaving it turning is a spinner forever, with no way to tell that the
               * load had already given up.
               */
                android.util.Log.e("AsyaLogs", "Could not show the logs", cause)
                lifecycleScope.launch { loadingIndicator.isVisible = false }
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        if (loadingIndicator.isVisible) return

        when (item.itemId) {
            R.id.menu_save -> {
                val date = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.getDefault()).format(Date())
                saveSAFLauncher.launch("asya_logs_$date.log")
            }

            R.id.menu_delete -> {
                ServiceClient.clearLogs()
                updateLogs()
            }

            R.id.menu_filter_debug -> {
                item.isChecked = true
                PrefManager.logFilter_level = 0
                updateLogs()
            }

            R.id.menu_filter_info -> {
                item.isChecked = true
                PrefManager.logFilter_level = 1
                updateLogs()
            }

            R.id.menu_filter_warn -> {
                item.isChecked = true
                PrefManager.logFilter_level = 2
                updateLogs()
            }

            R.id.menu_filter_error -> {
                item.isChecked = true
                PrefManager.logFilter_level = 3
                updateLogs()
            }

            R.id.menu_reverse_order -> {
                item.isChecked = !item.isChecked
                PrefManager.logFilter_reverseOrder = item.isChecked
                updateLogs()
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
            title = getString(R.string.title_logs),
            menuRes = R.menu.menu_logs,
            onMenuOptionSelected = this::onMenuOptionSelected,
        )
        toolbar.title = null

        with(toolbar.menu) {
            when (PrefManager.logFilter_level) {
                0 -> findItem(R.id.menu_filter_debug).isChecked = true
                1 -> findItem(R.id.menu_filter_info).isChecked = true
                2 -> findItem(R.id.menu_filter_warn).isChecked = true
                3 -> findItem(R.id.menu_filter_error).isChecked = true
            }
            findItem(R.id.menu_reverse_order).isChecked = PrefManager.logFilter_reverseOrder
        }
    }
}
