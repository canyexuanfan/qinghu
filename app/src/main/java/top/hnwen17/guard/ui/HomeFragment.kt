package top.hnwen17.guard.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import top.hnwen17.guard.core.*
import top.hnwen17.guard.databinding.FragmentHomeBinding
import top.hnwen17.guard.databinding.ItemRecentBinding

class HomeFragment : BoundFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {
    private var lastRecords: List<ProtectionRecord>? = null
    private var guidePending = false // QH-P20：延迟复核进行中（防重复 postDelayed）
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbarAction.setOnClickListener { host.navigate("settings") }
        binding.todayPill.setOnClickListener {
            // QH-P17：安装即用引导——无障碍未连接时胶囊为开启入口；已连接则进记录页
            val st = model.state.value
            val a11y = top.hnwen17.guard.platform.CapabilityRepository.accessibilityState.value
            if (!st.preview && a11y.availability != Availability.ACTIVE) guideEnable()
            else { model.filterApp(null); model.onlyToday(true); host.navigate("records") }
        }
        binding.allRecords.setOnClickListener { model.filterApp(null); host.navigate("records") }
        binding.shield.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        binding.shield.contentDescription = "暂停或恢复全部保护策略"
        binding.shield.setOnClickListener {
            val paused = model.state.value.settings.paused
            AlertDialog.Builder(requireContext()).setTitle(if(paused) "恢复保护策略" else "暂停保护策略")
                .setMessage("此操作修改全局策略：暂停后轻护不会自动点击任何广告控件；恢复后重新生效。")
                .setPositiveButton(if(paused) "恢复" else "暂停") { _, _ -> model.repository.update { it.copy(paused = !paused) } }
                .setNegativeButton("取消", null).show()
        }
        observe(model.state) { state ->
            val active = Capability.entries.count { state.effective(it) == Availability.ACTIVE }
            val limited = Capability.entries.count { state.effective(it) == Availability.LIMITED }
            binding.heroTitle.text = when { state.loading -> "加载中"; state.settings.paused -> "已暂停"; active > 0 -> "防护中"; limited > 0 -> "部分保护"; else -> "待启用" }
            binding.heroSubtitle.text = when {
                state.loading -> "正在读取本地设置"
                state.settings.paused -> "点击盾牌恢复保护策略"
                active > 0 -> "$active 项保护能力正在运行" + if(limited > 0) " · $limited 项受限" else ""
                else -> "开启无障碍服务后自动防护"
            }
            binding.shield.muted = active == 0
            val summary = state.summaryOverride ?: summarize(state.records) // 真实记录经 GuardRepository 汇入 state.records
            binding.todayPill.text = "今日已保护 ${summary.total} 次  ›"
            binding.cleanCount.text = summary.cleaned.toString()
            binding.touchCount.text = summary.touches.toString()
            binding.otherCount.text = summary.others.toString()
            listOf(binding.cleaner, binding.touch, binding.sensor, binding.jump).zip(Capability.entries).forEach { (row, cap) ->
                row.bind(cap, state.effective(cap)) { showCapability(cap) }
            }
            val records = state.records.take(3)
            if (records != lastRecords) {
                binding.recentList.removeAllViews()
                records.forEach { record ->
                    val item = ItemRecentBinding.inflate(layoutInflater, binding.recentList, false)
                    showRecent(item, record); binding.recentList.addView(item.root)
                }
                lastRecords = records
            }
            binding.recentEmpty.isVisible = records.isEmpty()
            // QH-P20 安装即用引导：服务绑定晚于首帧（冷启动竞态）——首帧不弹，
            // 延迟 2.5s 复核，仍离线才弹一次；已连接则静默（用户反馈：已开启也弹）
            if (!state.preview && !top.hnwen17.guard.platform.CapabilityRepository.guideShown
                && !guidePending
                && top.hnwen17.guard.platform.CapabilityRepository.accessibilityState.value.availability != Availability.ACTIVE
            ) {
                guidePending = true
                binding.root.postDelayed({
                    guidePending = false
                    if (!isAdded || top.hnwen17.guard.platform.CapabilityRepository.guideShown) return@postDelayed
                    val a = top.hnwen17.guard.platform.CapabilityRepository.accessibilityState.value
                    if (a.availability != Availability.ACTIVE) {
                        top.hnwen17.guard.platform.CapabilityRepository.guideShown = true
                        guideEnable()
                    }
                }, 2500)
            }
        }
    }
    /** QH-P17 安装即用：开启引导（可退出、直达系统设置、无强迫循环）。 */
    private fun guideEnable() {
        AlertDialog.Builder(requireContext())
            .setTitle("开启轻护保护")
            .setMessage("轻护通过系统无障碍服务识别广告并自动关闭。\n开启只需两步：\n1. 在系统列表中找到「轻护」并打开开关；\n2. 返回本页即自动生效。\n\n所有识别均在本机完成，不上传任何屏幕内容；随时可关闭。")
            .setPositiveButton("去系统设置开启") { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    private fun showCapability(cap: Capability) {
        val state = model.state.value
        val requested = cap in state.settings.enabled
        val a11y = top.hnwen17.guard.platform.CapabilityRepository.accessibilityState.value
        val statusLine = when {
            state.preview -> "本构建使用示例能力状态，仅预览原生界面交互。"
            a11y.availability == Availability.ACTIVE -> "无障碍服务已连接，防护执行器工作中。通用规则将自动识别并处理可安全关闭的广告。"
            else -> "无障碍服务未连接。请在系统设置中开启轻护的无障碍服务，开启后防护自动生效。"
        }
        AlertDialog.Builder(requireContext()).setTitle(cap.title)
            .setMessage("${cap.description.replace('\n', ' ')}\n\n当前状态：${statusLabel(state.effective(cap))}\n\n$statusLine")
            .setPositiveButton(if(requested) "关闭策略" else "开启策略") { _, _ -> model.repository.setCapability(cap, !requested) }
            .setNegativeButton("去无障碍设置") { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }
    override fun onDestroyView() { lastRecords = null; super.onDestroyView() }
}
