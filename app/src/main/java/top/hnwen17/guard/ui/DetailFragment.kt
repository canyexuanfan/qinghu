package top.hnwen17.guard.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import top.hnwen17.guard.R
import top.hnwen17.guard.core.*
import top.hnwen17.guard.databinding.FragmentDetailBinding

class DetailFragment : BoundFragment<FragmentDetailBinding>(FragmentDetailBinding::inflate) {
    private val appId get() = requireArguments().getString("appId").orEmpty()
    private var iconJob: Job? = null
    private var iconId: String? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.ensureAppsLoaded() // QH-P04-09：应用目录按需加载
        binding.back.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.toolbarAction.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle("恢复本应用默认策略？")
                .setMessage("仅恢复本应用的开关与保护强度，不修改其他应用。")
                .setPositiveButton("恢复") { _, _ -> model.repository.restoreApp(appId) }.setNegativeButton("取消", null).show()
        }
        observe(model.state) { state ->
            val app = state.apps.find { it.id == appId }
            val policy = state.policy(appId)
            binding.appName.text = app?.name ?: if(state.loading) "加载中" else "应用不可用"
            if(app != null && iconId != app.id) {
                iconJob?.cancel(); iconJob = icons.bind(binding.appIcon, app, viewLifecycleOwner.lifecycleScope); iconId = app.id
            }
            val current = if(policy.enabled.isEmpty() || state.settings.paused) Availability.DISABLED
                else policy.enabled.map { state.effective(it, appId) }.firstOrNull { it == Availability.ACTIVE }
                    ?: policy.enabled.map { state.effective(it, appId) }.firstOrNull() ?: Availability.DISABLED
            binding.appState.text = if(current == Availability.ACTIVE) "已保护" else statusLabel(current)
            binding.appState.setTextColor(ContextCompat.getColor(requireContext(), if(current == Availability.ACTIVE) R.color.green else R.color.sub))
            binding.stateIcon.alpha = if(current == Availability.ACTIVE) 1f else .4f
            listOf(binding.cleaner, binding.touch, binding.sensor, binding.jump).zip(Capability.entries).forEach { (row, cap) ->
                row.bind(cap, cap in policy.enabled) { enabled -> model.repository.setCapability(cap, enabled, appId) }
                row.toggle.isEnabled = app != null && !state.loading
            }
            binding.strength.title.text = "保护强度"
            binding.strength.subtitle.text = "根据应用特点自动调整各项保护策略"
            binding.strength.value.text = policy.strength.label
            binding.strength.root.setOnClickListener {
                var selection = policy.strength.ordinal
                AlertDialog.Builder(requireContext()).setTitle("保护强度")
                    .setSingleChoiceItems(Strength.entries.map { it.label }.toTypedArray(), selection) { _, which -> selection = which }
                    .setPositiveButton("保存") { _, _ -> model.repository.setStrength(appId, Strength.entries[selection]) }
                    .setNegativeButton("取消", null).show()
            }
            binding.records.title.text = "本应用保护记录"
            binding.records.subtitle.text = "查看近期的保护记录"
            val count = state.appRecordCounts[appId] ?: state.records.count { it.appId == appId }
            binding.records.value.text = "$count 次"
            binding.records.value.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
            binding.records.root.setOnClickListener {
                model.filterApp(appId); model.filterType(null); host.navigate("records")
            }
            binding.note.text = if(state.preview) "原生预览构建：状态与记录为示例；开关和强度会在本机保存。" else "开关保存本应用的保护策略。无障碍服务已连接时自动生效，通用规则将匹配已知广告模式并自动处理。"
        }
    }
    override fun onDestroyView() { iconJob?.cancel(); iconId = null; super.onDestroyView() }
}
