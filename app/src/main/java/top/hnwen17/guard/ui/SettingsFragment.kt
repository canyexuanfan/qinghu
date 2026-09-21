package top.hnwen17.guard.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import top.hnwen17.guard.BuildConfig
import top.hnwen17.guard.R
import top.hnwen17.guard.core.*
import top.hnwen17.guard.core.rules.RuleSignatureVerifier
import top.hnwen17.guard.databinding.FragmentSettingsBinding
import top.hnwen17.guard.databinding.ItemSettingSwitchBinding

class SettingsFragment : BoundFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.brandCard.setOnClickListener { about() }
        observe(model.state) { state ->
            binding.brandVersion.text = if(state.preview) "版本 1.0.0" else "版本 ${BuildConfig.VERSION_NAME}"
            binding.buildNotice.setText(if(state.preview) R.string.preview_banner else R.string.integration_banner)
            val base = state.effective(Capability.CLEANER)
            val enhanced = state.effective(Capability.SENSOR)
            binding.baseService.bind("基础保护", "提供广告净化、防误触等基础能力", statusLabel(base), R.drawable.status_shield, base, false) {
                explain("基础保护", "通用跳过规则已内置并随无障碍服务自动工作。在支持的应用中遇到可识别广告时会自动关闭。")
            }
            binding.accessibility.bind("无障碍服务", "用于识别页面内容和执行保护", a11yLabel(base, state), R.drawable.service_accessibility, base, false) {
                guideAccessibility(base, state)
            }
            binding.enhancement.bind("增强保护", "提供防摇一摇、异常跳转等增强能力", statusLabel(enhanced), R.drawable.service_bolt, enhanced, false) {
                explain("增强保护", "防摇一摇与跳转拦截已通过无障碍通道工作；系统级增强（Shizuku）为可选第二道防线。")
            }
            binding.shizuku.bind("系统增强服务", "需要额外的系统授权",
                if(state.preview && enhanced == Availability.ACTIVE) "正常" else shizukuLabel(enhanced, state), R.drawable.service_phone, enhanced, false) {
                guideShizuku(enhanced, state)
            }
            bindSettingSwitch(binding.autoUpdate, "规则自动更新", if(state.preview) "自动获取最新的广告识别规则" else "保存更新偏好", R.drawable.ic_records, state.settings.autoUpdate) {
                value -> model.repository.update { it.copy(autoUpdate = value) }
            }
            binding.ruleVersion.bind("规则版本", if(state.preview) "2026.09.12 · 已是最新版本" else "内置通用规则 v3（25 条）已随应用发布", "", R.drawable.ic_records) {
                explain("规则版本", if(state.preview) "内置通用规则已随应用发布，详细规则见 RULE_SCHEMA.md。" else "内置通用规则 v3 已随应用发布（覆盖常见开屏跳过/广告关闭文案与主流广告 SDK 控件）；自定义规则包可通过导入功能添加。")
            }
            binding.localOnly.bind("所有识别均在本机完成", "不上传任何屏幕内容", "已开启", R.drawable.ic_lock, Availability.ACTIVE, false) {
                explain("本地处理与隐私", "广告识别与执行全部在本机完成，不上传任何屏幕内容；网络权限仅在显式开启在线规则更新后使用。")
            }
            binding.networkFilter.bind("网络广告过滤", "使用本地 VPN 过滤广告域名", "", R.drawable.ic_globe) {
                explain("网络广告过滤", "这是后续可选能力，目前不创建 VPN、不接管网络、不下载广告规则。")
            }
            binding.compatibility.bind("精细化设置", "针对不同设备的兼容性选项", "", R.drawable.ic_sliders) {
                val app = requireContext().applicationContext as top.hnwen17.guard.GuardApplication
                val recent = model.runtime?.recentActionsSnapshot().orEmpty()
                val failed = app.recordStore.all.value.filter { it.outcome == top.hnwen17.guard.core.records.ProtectionOutcome.FAILED }
                val observations = app.observeStore.all.value
                val msg = buildString {
                    append(Capability.entries.joinToString("\n") { "${it.title}：${statusLabel(state.effective(it))}" })
                    if (recent.isNotEmpty()) append("\n\n最近规则动作：\n").append(recent.takeLast(5).joinToString("\n"))
                    if (failed.isNotEmpty()) append("\n\n存在失败规则（可停用）：").append(failed.map { it.ruleId }.distinct().joinToString())
                    // QH-P18 观察日志：出现广告必留痕（成功/失败/待适配），可导出反馈补规则
                    if (observations.isNotEmpty()) append("\n\n观察日志 ${observations.size} 条（记录页可见「识别到广告窗口」）：\n")
                        .append(observations.takeLast(3).joinToString("\n") { o ->
                            "· ${o.packageName.substringAfterLast('.')} ${o.reason}" + (o.samples.firstOrNull()?.let { "「$it」" } ?: "")
                        })
                    else append("\n\n观察日志：暂无。出现广告但无记录时，说明引擎未收到该窗口事件。")
                }
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("能力接入状态")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                if (failed.isNotEmpty()) {
                    dialog.setNeutralButton("停用失败规则") { _, _ ->
                        failed.map { it.ruleId }.distinct().forEach { app.ruleRepository.recordFailureAndDisable(it) }
                    }
                }
                dialog.show()
            }
            binding.diagnostics.bind("诊断", "查看运行状态与日志", "", R.drawable.ic_info) { diagnostic() }
            // QH-P16（ADR-003）：在线更新默认关闭，用户显式开启+填源URL才拉取，强制验签
            val updateApp = requireContext().applicationContext as top.hnwen17.guard.GuardApplication
            updateApp.updateSettings // QH-P16 初始化（lazy ensure）
            val app = updateApp
            binding.lab.bind(
                "在线规则更新（默认关闭）",
                "开启后将从你提供的 HTTPS 源拉取规则包（强制验签）；关闭时无任何网络请求",
                if (app.updateSettings.enabled.value) "已开启" else "已关闭",
                R.drawable.ic_globe,
                if (app.updateSettings.enabled.value) top.hnwen17.guard.core.Availability.ACTIVE else top.hnwen17.guard.core.Availability.DISABLED
            ) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("在线规则更新")
                    .setMessage("默认关闭。开启需提供 HTTPS 规则源 URL；拉取内容强制 ECDSA 验签+严格解析，验证失败整包拒绝。")
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("重新拉取") { _, _ ->
                        val url = app.updateSettings.url.value
                        if (url.isEmpty()) {
                            explain("未配置源", "请先通过可编辑存储写入更新源 URL（当前版本源配置持久化入口待接）。")
                        } else {
                            val trustAnchor = RuleSignatureVerifier.publicKeyFromX509(
                                RuleSignatureVerifier.fromHex(
                                    requireContext().assets.open("rules/update_trust_anchor.hex").use {
                                        it.bufferedReader().readText().trim()
                                    }
                                ) ?: throw IllegalArgumentException("trust anchor")
                            )!!
                            val client = top.hnwen17.guard.platform.update.RuleUpdateClient(requireContext())
                            val importManager = top.hnwen17.guard.data.RuleImportManager()
                            val sigUrl = url.trimEnd('/') + ".sig"
                            val r = client.fetchAndImport(url, sigUrl, trustAnchor, importManager)
                            val msg = when (r) {
                                is top.hnwen17.guard.platform.update.RuleUpdateClient.Result.Ok ->
                                    "成功：${r.ruleIds.size} 条规则（${r.bytes} 字节），默认停用，可在诊断确认后启用。"
                                is top.hnwen17.guard.platform.update.RuleUpdateClient.Result.Error ->
                                    "失败[${r.stage}]：${r.message}"
                            }
                            explain("拉取结果", msg)
                        }
                    }
                    .show()
            }
            binding.lab.bind("实验性功能", "体验仍在开发中的新功能", "", R.drawable.ic_lab) {
                if(state.preview) AlertDialog.Builder(requireContext()).setTitle("原生界面状态预览")
                    .setItems(arrayOf("正常保护", "增强授权失效", "全部待接入", "能力异常", "跳转部分保护")) { _, i -> model.repository.previewScenario(i) }.show()
                else AlertDialog.Builder(requireContext()).setTitle("实验性功能")
                    .setMessage("标准构建不包含示例能力控制器。\n\n可将规则包 JSON 放入 Android/data/top.hnwen17.guard/files/rules/ 目录（默认停用，可在诊断确认加载）。")
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("重新加载规则") { _, _ ->
                        model.runtime.reload(requireContext())
                        val n = model.runtime.recentActionsSnapshot().count { it.startsWith("generic pack") || it.startsWith("imported") }
                        explain("重新加载", "已完成。最近日志：\n${model.runtime.recentActionsSnapshot().takeLast(5).joinToString("\n")}")
                    }
                    .show()
            }
            bindSettingSwitch(binding.reduceMotion, "减少动效", "关闭过渡动画，品牌图案保持静态", R.drawable.ic_sliders, state.settings.reduceMotion) {
                value -> model.repository.update { it.copy(reduceMotion = value) }
            }
            binding.about.bind("关于我们", "轻护 · 轻松一护，纯净如初", "", R.drawable.ic_info) { about() }
            binding.dataManagement.bind("本机数据", "清空记录或恢复默认设置", "", R.drawable.ic_records) { dataActions() }
        }
    }
    private fun bindSettingSwitch(row: ItemSettingSwitchBinding, title: String, subtitle: String, icon: Int,
                                  value: Boolean, changed: (Boolean) -> Unit) {
        row.title.text = title; row.description.text = subtitle; row.icon.setImageResource(icon)
        row.toggle.contentDescription = title
        row.toggle.setOnCheckedChangeListener(null); row.toggle.isChecked = value
        row.toggle.setOnCheckedChangeListener { _, checked -> changed(checked) }
    }
    private fun explain(title: String, message: String) {
        AlertDialog.Builder(requireContext()).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show()
    }
    /** QH-P05-06：真实探测状态 + 可退出的系统设置引导，无强迫循环。 */
    private fun a11yLabel(base: Availability, state: AppState): String =
        if (state.preview && base == Availability.ACTIVE) "正常"
        else statusLabel(base)

    /** QH-P05-08：Shizuku 真实状态与按需授权；未安装/拒绝均如实展示，不假绿。 */
    private fun shizukuLabel(base: Availability, state: AppState): String =
        if (state.preview && base == Availability.ACTIVE) "正常" else statusLabel(base)

    private fun guideShizuku(base: Availability, state: AppState) {
        if (state.preview) {
            explain("系统增强服务", "预览模式展示设计示例状态；真实授权以 standard 版本中 Shizuku 桥接的实际连接结果为准。")
            return
        }
        val installed = top.hnwen17.guard.platform.shizuku.ShizukuBridge.managerInstalled(requireContext())
        val message = when {
            !installed -> "未安装 Shizuku，完全不影响使用——当前全部防护（开屏跳过、摇一摇落地页自动返回、跳转拦截、防误触）均已通过无障碍通道完整工作。Shizuku 通道为未来系统级增强预留，且因系统限制它也无法阻止应用读取摇一摇传感器。"
            base == Availability.ACTIVE -> "Shizuku 已连接并授权（只读系统能力可用），本应用不执行任何修改系统设置的命令。当前防护不依赖此通道。"
            else -> "Shizuku 已安装但未授权。当前防护不依赖此通道，可随时授权或忽略。"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("系统增强服务（可选，当前非必需）")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .setNegativeButton(if (installed && base != Availability.ACTIVE) "去授权" else "知道了") { _, _ ->
                if (installed && base != Availability.ACTIVE) {
                    if (!top.hnwen17.guard.platform.shizuku.ShizukuBridge.requestPermission()) {
                        explain("无法发起授权", "Shizuku 服务未运行。请先打开 Shizuku 管理器并启动服务，再回到本页重试。")
                    }
                }
            }
            .show()
    }

    private fun guideAccessibility(base: Availability, state: AppState) {
        if (state.preview) {
            explain("无障碍服务", "预览模式展示设计示例状态；真实授权以 standard 版本在本机系统设置中的结果为准，这里不模拟授权成功。")
            return
        }
        val message = if (base == Availability.ACTIVE)
            "无障碍服务已连接，正在本机识别窗口变化并执行保护。可随时在系统设置中关闭。"
        else
            "无障碍服务未连接。开启后，轻护在本机识别窗口变化以关闭可安全识别的广告、保护误触区域。不上传任何屏幕内容；拒绝或随时撤销都不影响浏览。"
        AlertDialog.Builder(requireContext())
            .setTitle("无障碍服务")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .setNegativeButton("系统设置") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("防杀后台") { _, _ -> requestBatteryWhitelist() }
            .show()
    }

    /** QH-P17 存活加固：申请电池优化白名单，降低系统回收无障碍服务进程的概率。 */
    private fun requestBatteryWhitelist() {
        try {
            startActivity(Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${requireContext().packageName}")))
        } catch (e: Exception) {
            explain("无法打开系统弹窗", "请手动设置：系统设置 → 电池/省电 → 找到「轻护」→ 允许后台运行（或设为无限制/不优化）。")
        }
    }
    private fun about() = explain("轻护", "远离广告干扰 · 守护纯净体验\n\nKotlin + Android Views/XML + ViewBinding\n实际构建版本：${BuildConfig.VERSION_NAME}\n作者：十七°\n\n" +
        if(BuildConfig.PREVIEW_DATA) "预览中的应用、版本、统计和防护状态均为设计示例。页面为真实原生控件，防护后端未接入。" else "防护执行器已接入，通用规则自动工作中。所有识别均在本机完成。")
    private fun diagnostic() {
        val state = model.diagnostics.value
        AlertDialog.Builder(requireContext()).setTitle("计算诊断")
            .setMessage(state.result + "\n\n运行后可关闭弹窗继续操作。结果保留在这里，不创建常驻任务。")
            .setPositiveButton(if(state.busy) "计算中" else "运行一次") { _, _ -> if(!state.busy) model.runDiagnostic() }
            .setNegativeButton("返回", null).show()
    }
    private fun dataActions() {
        AlertDialog.Builder(requireContext()).setTitle("本机数据")
            .setItems(arrayOf("清空记录", "恢复本构建的默认设置", "导出记录（防护+观察，按应用/按时间）", "清空观察日志")) { _, option ->
                when (option) {
                    0 -> AlertDialog.Builder(requireContext()).setTitle("确认操作")
                        .setMessage("清空此构建的保护记录展示与统计。")
                        .setPositiveButton("确认") { _, _ -> model.repository.clearRecords() }
                        .setNegativeButton("取消", null).show()
                    1 -> AlertDialog.Builder(requireContext()).setTitle("确认操作")
                        .setMessage("重置本构建的开关、强度与记录，不修改其他应用。")
                        .setPositiveButton("确认") { _, _ -> model.repository.reset() }
                        .setNegativeButton("取消", null).show()
                    2 -> AlertDialog.Builder(requireContext()).setTitle("导出内容")
                        .setItems(arrayOf("全部（防护记录 + 拦截失败）", "仅防护记录（已关闭/已保护）", "仅拦截失败记录")) { _, which ->
                            val frag = ObserveExportFragment().apply {
                                arguments = android.os.Bundle().apply { putInt("scope", which) }
                            }
                            requireActivity().supportFragmentManager.beginTransaction()
                                .setReorderingAllowed(true)
                                .replace(top.hnwen17.guard.R.id.content, frag, "observe_export")
                                .addToBackStack("observe_export")
                                .commit()
                        }.show()
                    3 -> (requireContext().applicationContext as top.hnwen17.guard.GuardApplication).observeStore.clear()
                }
            }.show()
    }
}
