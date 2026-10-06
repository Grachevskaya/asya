package io.github.grachevskaya.asya.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.common.Utils.conflictedModules
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.navController
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.databinding.FragmentFixIssueBinding
import io.github.grachevskaya.asya.ui.adapter.FixIssueAdapter

class FixIssueFragment : Fragment(R.layout.fragment_fix_issue) {

    private val binding by viewBinding(FragmentFixIssueBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.home_migrate_data_fix_button),
            )
            setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationOnClickListener { navController.popBackStack() }
        }

       with(binding.list) {
           layoutManager = LinearLayoutManager(requireContext())
           adapter = FixIssueAdapter(requireContext(), conflictedModules.filter {
               ServiceClient.getPackageInfo(it, 0) != null
           })
       }

        setEdge2EdgeFlags(binding.root)
    }

    @Suppress("unused")
    companion object {
        @JvmStatic
        fun newInstance() = FixIssueFragment()
    }
}
