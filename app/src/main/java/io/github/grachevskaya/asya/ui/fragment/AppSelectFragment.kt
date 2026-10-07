package io.github.grachevskaya.asya.ui.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.FragmentAppSelectBinding
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.ui.adapter.AppSelectAdapter
import io.github.grachevskaya.asya.ui.util.ThemeUtils.homeItemBackgroundColor
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor
import io.github.grachevskaya.asya.ui.util.navController
import io.github.grachevskaya.asya.ui.util.registerOnBackCallback
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.util.PackageHelper
import kotlinx.coroutines.launch

abstract class AppSelectFragment : Fragment(R.layout.fragment_app_select) {
    protected val binding by viewBinding(FragmentAppSelectBinding::bind)

    protected abstract val firstComparator: Comparator<String>
    protected abstract val adapter: AppSelectAdapter
    protected open val showBackButton = true

    private var search = ""

    protected open fun onBack() {
        navController.navigateUp()
    }

    protected fun applyFilter() {
        adapter.filter.filter(search)
    }

    protected open fun sortList() {
        lifecycleScope.launch {
            PackageHelper.sortList(firstComparator)
            applyFilter()
        }
    }

    protected open fun invalidateCache() {
        PackageHelper.invalidateCache()
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_show_system -> {
                item.isChecked = !item.isChecked
                PrefManager.appFilter_showSystem = item.isChecked
            }

            R.id.menu_sort_by_label -> {
                item.isChecked = true
                PrefManager.appFilter_sortMethod = PrefManager.SortMethod.BY_LABEL
            }

            R.id.menu_sort_by_package_name -> {
                item.isChecked = true
                PrefManager.appFilter_sortMethod = PrefManager.SortMethod.BY_PACKAGE_NAME
            }

            R.id.menu_sort_by_install_time -> {
                item.isChecked = true
                PrefManager.appFilter_sortMethod = PrefManager.SortMethod.BY_INSTALL_TIME
            }

            R.id.menu_sort_by_update_time -> {
                item.isChecked = true
                PrefManager.appFilter_sortMethod = PrefManager.SortMethod.BY_UPDATE_TIME
            }

            R.id.menu_reverse_order -> {
                item.isChecked = !item.isChecked
                PrefManager.appFilter_reverseOrder = item.isChecked
            }
        }

        sortList()
    }

    open fun getFragmentTitle() = getString(R.string.title_app_select)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        if (showBackButton) {
            registerOnBackCallback { onBack() }
        }

        binding.collapsing.title = getFragmentTitle()
        setupToolbar(
            toolbar = binding.toolbar,
            title = getFragmentTitle(),
            navigationIcon = if (showBackButton) R.drawable.baseline_arrow_back_24 else null,
            navigationOnClick = if (showBackButton) View.OnClickListener { onBack() } else null,
            menuRes = R.menu.menu_app_list,
            onMenuOptionSelected = this::onMenuOptionSelected,
        )
        binding.toolbar.title = null

        with(binding.toolbar.menu) {
            val searchView = findItem(R.id.menu_search).actionView as SearchView
            searchView.setOnQueryTextListener(
                object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String) = false

                    override fun onQueryTextChange(newText: String): Boolean {
                        search = newText
                        applyFilter()
                        return true
                    }
                },
            )

            findItem(R.id.menu_show_system).isChecked = PrefManager.appFilter_showSystem
            when (PrefManager.appFilter_sortMethod) {
                PrefManager.SortMethod.BY_LABEL -> findItem(R.id.menu_sort_by_label).isChecked = true
                PrefManager.SortMethod.BY_PACKAGE_NAME -> findItem(R.id.menu_sort_by_package_name).isChecked = true
                PrefManager.SortMethod.BY_INSTALL_TIME -> findItem(R.id.menu_sort_by_install_time).isChecked = true
                PrefManager.SortMethod.BY_UPDATE_TIME -> findItem(R.id.menu_sort_by_update_time).isChecked = true
            }
            findItem(R.id.menu_reverse_order).isChecked = PrefManager.appFilter_reverseOrder
        }
        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter

        with(binding.swipeRefresh) {
            setProgressBackgroundColorSchemeColor(homeItemBackgroundColor(true))
            setColorSchemeColors(
                themeColor(androidx.appcompat.R.attr.colorPrimary),
                themeColor(com.google.android.material.R.attr.colorSecondary),
                themeColor(com.google.android.material.R.attr.colorTertiary),
            )

            setOnRefreshListener {
                invalidateCache()
            }
        }

        adapter.registerAdapterDataObserver(
            EmptyDataObserver(
                binding.list,
                binding.listEmptyContainer.root,
            ),
        )

        lifecycleScope.launch {
            PackageHelper.isRefreshing
                .flowWithLifecycle(lifecycle)
                .collect { isRefreshing ->
                    binding.swipeRefresh.isRefreshing = isRefreshing

                    if (!isRefreshing) {
                        sortList()
                    }
                }
        }

        sortList()

        setEdge2EdgeFlags(binding.root)
    }

    // Credit: https://medium.com/nerd-for-tech/empty-dataset-in-recyclerview-ad86833dd5c6
    inner class EmptyDataObserver(
        private val recyclerView: RecyclerView,
        private val emptyView: View,
    ) : RecyclerView.AdapterDataObserver() {
        private val fragmentType by lazy {
            when (this@AppSelectFragment.javaClass) {
                ScopeFragment::class.java -> 0
                else -> 2
            }
        }

        private fun checkIfEmpty() {
            val emptyViewVisible = recyclerView.adapter!!.itemCount < 1
            emptyView.visibility = if (emptyViewVisible) View.VISIBLE else View.GONE
            if (emptyViewVisible) {
                emptyView.findViewById<TextView>(R.id.list_empty_text).text =
                    getString(
                        when (fragmentType) {
                            0 -> R.string.list_empty_no_enabled
                            else -> R.string.list_empty_no_apps
                        },
                    )
            }
            recyclerView.visibility = if (emptyViewVisible) View.GONE else View.VISIBLE
        }

        override fun onChanged() {
            super.onChanged()
            checkIfEmpty()
        }
    }
}
