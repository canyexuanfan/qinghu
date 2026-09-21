package top.hnwen17.guard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import top.hnwen17.guard.R
import top.hnwen17.guard.databinding.FragmentObserveExportBinding
import top.hnwen17.guard.databinding.ItemAppBinding
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 阶段3 导出页（用户设计）：
 * - 按应用/按时间 两个页签
 * - 按应用：每个应用可展开/折叠查看具体记录，应用级和单条记录均有 CheckBox
 * - 按时间：自上次导出/今天/全部 三档
 * - 全选 + 底部导出按钮
 */
class ObserveExportFragment : Fragment() {

    private var _binding: FragmentObserveExportBinding? = null
    private val binding get() = _binding!!
    private val appChecks = mutableMapOf<String, CheckBox>()
    private val timeChecks = mutableListOf<Pair<CheckBox, Long>>()
    private var lastExportAtMs = 0L
    private var allEntries = listOf<ExportEntry>()

    /** 统一的导出条目（防护记录或观察记录） */
    data class ExportEntry(
        val time: Long,
        val packageName: String,
        val appName: String,
        val kind: String,       // "防护" / "拦截失败"
        val action: String,     // "已关闭广告" / "拦截失败" / "未拦截"
        val detail: String,
        val selected: Boolean = true
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObserveExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireContext().applicationContext as top.hnwen17.guard.GuardApplication
        val pm = requireContext().packageManager
        val prefs = requireContext().getSharedPreferences("observe_export", android.content.Context.MODE_PRIVATE)
        lastExportAtMs = prefs.getLong("lastExportAtMs", 0L)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }

        // 分段页签
        fun selectTab(byApp: Boolean) {
            listOf(binding.tabByApp to byApp, binding.tabByTime to !byApp).forEach { (tab, selected) ->
                tab.isSelected = selected
                tab.setBackgroundResource(if (selected) R.drawable.bg_segment_selected else 0)
                tab.setTextColor(ContextCompat.getColor(requireContext(), if (selected) R.color.ink else R.color.sub))
                tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
            binding.appListScroll.isVisible = byApp
            binding.timeListScroll.isVisible = !byApp
            binding.selectAllBar.isVisible = byApp
        }
        binding.tabByApp.setOnClickListener { selectTab(true) }
        binding.tabByTime.setOnClickListener { selectTab(false) }
        selectTab(true)

        // 数据源：防护记录 + 观察记录 合并为统一的 ExportEntry 列表
        val protections = app.recordStore.all.value
        val observations = app.observeStore.all.value
        val entries = mutableListOf<ExportEntry>()

        for (r in protections) {
            val label = try { pm.getApplicationInfo(r.packageName, 0).loadLabel(pm).toString() } catch (_: Exception) { r.packageName }
            entries.add(ExportEntry(
                time = r.atEpochMs, packageName = r.packageName, appName = label,
                kind = "防护",
                action = when (r.outcome.name) {
                    "VERIFIED" -> "已关闭广告"
                    else -> r.outcome.name
                },
                detail = "规则 ${r.ruleId} ${r.outcome.name}"
            ))
        }
        for (o in observations) {
            val label = try { pm.getApplicationInfo(o.packageName, 0).loadLabel(pm).toString() } catch (_: Exception) { o.packageName }
            entries.add(ExportEntry(
                time = o.atEpochMs, packageName = o.packageName, appName = label,
                kind = "未拦截",
                action = "未拦截",
                detail = o.className
            ))
        }
        allEntries = entries.sortedByDescending { it.time }

        // 按应用分组
        val grouped = allEntries.groupBy { it.packageName }
        for ((pkg, list) in grouped.entries.sortedByDescending { it.value.size }) {
            val row = ItemAppBinding.inflate(layoutInflater)
            val label = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
            row.name.text = label
            row.status.text = "${list.size} 条"
            val latest = list.maxOfOrNull { it.time } ?: 0L
            val kinds = list.groupBy { it.kind }.entries.joinToString(" · ") { "${it.key} ${it.value.size}" }
            row.description.text = "最近 ${fmt.format(Date(latest))} · $kinds"
            try { row.icon.setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) { }
            row.chevron.isVisible = false
            val check = CheckBox(requireContext()).apply { isChecked = true }
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            check.setOnCheckedChangeListener { _, _ -> syncSelectAll() }
            binding.appList.addView(row.root)

            appChecks[pkg] = check
        }

        if (grouped.isEmpty()) {
            binding.appList.addView(TextView(requireContext()).apply {
                text = "暂无记录——引擎尚未看到疑似广告窗口"
                setPadding(0, 40, 0, 0)
            })
        }
        binding.selectAll.setOnCheckedChangeListener { _, checked -> appChecks.values.forEach { it.isChecked = checked } }

        // 按时间
        val ranges = listOf(
            Triple("自上次导出以来", if (lastExportAtMs > 0) "上次导出于 ${fmt.format(Date(lastExportAtMs))}" else "暂无上次导出，等同全部", lastExportAtMs),
            Triple("今天", "当日 00:00 起", dayStart),
            Triple("全部", "所有记录", 0L)
        )
        var checkedTime: CheckBox? = null
        for ((idx, range) in ranges.withIndex()) {
            val count = allEntries.count { it.time >= range.third }
            val row = ItemAppBinding.inflate(layoutInflater)
            row.name.text = range.first
            row.status.text = "$count 条"
            row.description.text = range.second
            row.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_records))
            row.chevron.isVisible = false
            val check = CheckBox(requireContext()).apply { isChecked = idx == 0 }
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) { checkedTime?.let { if (it !== check) it.isChecked = false }; checkedTime = check }
            }
            binding.timeList.addView(row.root)
            timeChecks.add(check to range.third)
        }

        binding.exportBtn.setOnClickListener { export(app, prefs) }
    }


    private fun syncSelectAll() {
        binding.selectAll.isChecked = appChecks.values.all { it.isChecked }
    }

    private fun export(app: top.hnwen17.guard.GuardApplication, prefs: android.content.SharedPreferences) {
        val since = if (binding.appListScroll.isVisible) 0L else timeChecks.firstOrNull { it.first.isChecked }?.second ?: 0L
        val inRange: (Long) -> Boolean = { since <= 0L || it >= since }
        val chosenPkgs = appChecks.filterValues { it.isChecked }.keys
        val byPkg: (String) -> Boolean = { binding.timeListScroll.isVisible || it in chosenPkgs }
        val selected = allEntries.filter { inRange(it.time) && byPkg(it.packageName) }
        if (selected.isEmpty()) {
            android.app.AlertDialog.Builder(requireContext()).setTitle("没有可选记录")
                .setMessage("所选范围内暂无记录。").setPositiveButton("知道了", null).show()
            return
        }
        val text = buildString {
            append("轻护记录导出 v${top.hnwen17.guard.BuildConfig.VERSION_NAME}（本机生成，仅供规则适配；共 ${selected.size} 条）\n")
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            for (e in selected) {
                append("\n[${fmt.format(Date(e.time))}] ${e.packageName} ${e.action}")
                if (e.detail.isNotEmpty()) append("\n  detail: ${e.detail}")
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_TITLE, "轻护记录导出")
        prefs.edit().putLong("lastExportAtMs", java.lang.System.currentTimeMillis()).apply()
        startActivity(Intent.createChooser(send, "导出记录"))
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
