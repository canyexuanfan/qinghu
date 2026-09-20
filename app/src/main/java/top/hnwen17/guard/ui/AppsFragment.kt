package top.hnwen17.guard.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import top.hnwen17.guard.R
import top.hnwen17.guard.core.AppSort
import top.hnwen17.guard.databinding.FragmentAppsBinding

class AppsFragment : BoundFragment<FragmentAppsBinding>(FragmentAppsBinding::inflate) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.ensureAppsLoaded() // QH-P04-09：应用目录按需加载
        val adapter = AppsAdapter(icons, viewLifecycleOwner.lifecycleScope,
            open = { host.openDetail(it) }, sort = { showSort(it) })
        adapter.stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter; binding.list.itemAnimator = null
        binding.search.setText(model.query.value)
        binding.search.doAfterTextChanged { if (model.query.value != it.toString()) model.search(it.toString()) }
        binding.toolbarAction.setOnClickListener {
            binding.search.requestFocus()
            requireContext().getSystemService(InputMethodManager::class.java).showSoftInput(binding.search, InputMethodManager.SHOW_IMPLICIT)
        }
        binding.userApps.setOnClickListener { model.showSystemApps(false) }
        binding.systemApps.setOnClickListener { model.showSystemApps(true) }
        observe(model.systemApps) { system ->
            listOf(binding.userApps to !system, binding.systemApps to system).forEach { (tab, selected) ->
                tab.isSelected = selected
                tab.setBackgroundResource(if(selected) R.drawable.bg_segment_selected else 0)
                tab.setTextColor(ContextCompat.getColor(requireContext(), if(selected) R.color.ink else R.color.sub))
                tab.setTypeface(null, if(selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        observe(model.state) { state ->
            binding.empty.text = if (state.loading) "正在读取本地应用…" else "没有找到应用"
            binding.empty.isVisible = state.loading || model.appRows.value.isEmpty()
        }
        observe(model.appRows) { rows ->
            adapter.submitList(rows)
            binding.empty.isVisible = rows.isEmpty() && !model.state.value.loading
        }
    }
    private fun showSort(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 0, 0, "按名称排序")
            menu.add(0, 1, 1, "有保护策略的应用优先")
            setOnMenuItemClickListener { item -> model.sort(if(item.itemId == 0) AppSort.NAME else AppSort.ENABLED_FIRST); true }
        }.show()
    }
    override fun onDestroyView() { binding.list.adapter = null; super.onDestroyView() }
}
