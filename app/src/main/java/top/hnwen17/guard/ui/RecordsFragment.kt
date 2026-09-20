package top.hnwen17.guard.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.hnwen17.guard.R
import top.hnwen17.guard.core.Capability
import top.hnwen17.guard.databinding.FragmentRecordsBinding

class RecordsFragment : BoundFragment<FragmentRecordsBinding>(FragmentRecordsBinding::inflate) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // QH-P08-06：记录详情对话框——展示规则与版本，提供"停用此规则/停用本应用"；不承诺撤销已点击行为
        val adapter = RecordsAdapter(onRecordClick = { record ->
            val app = requireContext().applicationContext as top.hnwen17.guard.GuardApplication
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("防护记录详情")
                .setMessage("应用：${record.appName}\n规则：${record.detail}\n\n停用后该规则不再自动执行；已点击过的行为无法撤销。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("停用此规则") { _, _ ->
                    app.ruleRepository.recordFailureAndDisable(record.detail.substringAfter("规则 ").substringBefore(" v"))
                }
                .setNegativeButton("停用本应用") { _, _ ->
                    app.repository.setCapability(top.hnwen17.guard.core.Capability.CLEANER, false, record.appId)
                }
                .show()
        })
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter; binding.list.itemAnimator = null
        val chips = listOf(binding.chipAll to null, binding.chipCleaner to Capability.CLEANER,
            binding.chipTouch to Capability.TOUCH, binding.chipSensor to Capability.SENSOR, binding.chipJump to Capability.JUMP)
        chips.forEach { (chip, cap) -> chip.setOnClickListener { model.filterType(cap) } }
        observe(model.type) { type ->
            chips.forEach { (chip, cap) ->
                val selected = type == (cap?.name ?: "ALL")
                chip.setBackgroundResource(if(selected) R.drawable.bg_selected_blue else R.drawable.bg_gray)
                chip.setTextColor(ContextCompat.getColor(requireContext(), if(selected) R.color.white else R.color.sub))
                chip.isSelected = selected
            }
        }
        binding.toolbarAction.setOnClickListener { filters() }
        observe(model.appFilter) { renderFilters() }
        observe(model.todayOnly) { renderFilters() }
        observe(model.records) { records -> adapter.submitList(timeline(records)); binding.empty.isVisible = records.isEmpty() }
        binding.list.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) model.nextPage()
            }
        })
    }
    private fun renderFilters() {
        val id = model.appFilter.value
        val app = model.state.value.apps.find { it.id == id }?.name ?: if(id.isBlank()) "全部应用" else id
        binding.filterLabel.text = "$app · " + if(model.todayOnly.value) "今天" else "全部日期"
        binding.filterLabel.isVisible = id.isNotBlank() || model.todayOnly.value
    }
    private fun filters() {
        AlertDialog.Builder(requireContext()).setTitle("筛选保护记录")
            .setItems(arrayOf("选择应用", if(model.todayOnly.value) "显示全部日期" else "仅看今天", "清除筛选")) { _, which ->
                when(which) {
                    0 -> {
                        // QH-P18：只列出守护记录里实际出现过的应用（筛选即「有记录的应用」）
                        val seen = LinkedHashMap<String, String>()
                        model.state.value.records.forEach { r -> if (r.appId !in seen) seen[r.appId] = r.appName }
                        val ids = seen.keys.toList()
                        AlertDialog.Builder(requireContext()).setTitle("选择应用")
                            .setItems((listOf("全部应用") + seen.values).toTypedArray()) { _, i ->
                                model.filterApp(if(i == 0) null else ids[i-1])
                            }.show()
                    }
                    1 -> model.onlyToday(!model.todayOnly.value)
                    2 -> { model.filterApp(null); model.filterType(null); model.onlyToday(false) }
                }
            }.show()
    }
    override fun onDestroyView() { binding.list.clearOnScrollListeners(); binding.list.adapter = null; super.onDestroyView() }
}
