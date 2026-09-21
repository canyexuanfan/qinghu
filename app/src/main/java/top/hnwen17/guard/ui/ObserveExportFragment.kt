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
 * 导出页：按应用/按时间展开折叠查看具体记录，每条含开关状态和规则信息。
 */
class ObserveExportFragment : Fragment() {

    private var _b: FragmentObserveExportBinding? = null
    private val b get() = _b!!
    private val appChecks = mutableMapOf<String, CheckBox>()
    private val expandedApps = mutableSetOf<String>()
    private val timeChecks = mutableListOf<Pair<CheckBox, Long>>()
    private var lastExportAtMs = 0L
    private var allEntries = listOf<ExportEntry>()

    data class ExportEntry(
        val time: Long, val pkg: String, val appName: String,
        val kind: String, val action: String, val detail: String,
        val switchState: String = "" // "内置开" / "订阅开" / "全关" 等
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentObserveExportBinding.inflate(inflater, container, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireContext().applicationContext as top.hnwen17.guard.GuardApplication
        val pm = requireContext().packageManager
        val prefs = requireContext().getSharedPreferences("observe_export", android.content.Context.MODE_PRIVATE)
        lastExportAtMs = prefs.getLong("lastExportAtMs", 0L)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        fun selectTab(byApp: Boolean) {
            listOf(b.tabByApp to byApp, b.tabByTime to !byApp).forEach { (tab, sel) ->
                tab.isSelected = sel
                tab.setBackgroundResource(if (sel) R.drawable.bg_segment_selected else 0)
                tab.setTextColor(ContextCompat.getColor(requireContext(), if (sel) R.color.ink else R.color.sub))
                tab.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            }
            b.appListScroll.isVisible = byApp; b.timeListScroll.isVisible = !byApp; b.selectAllBar.isVisible = byApp
        }
        b.tabByApp.setOnClickListener { selectTab(true) }
        b.tabByTime.setOnClickListener { selectTab(false) }
        selectTab(true)

        val entries = mutableListOf<ExportEntry>()
        for (r in app.recordStore.all.value) {
            val lbl = try { pm.getApplicationInfo(r.packageName, 0).loadLabel(pm).toString() } catch (_: Exception) { r.packageName }
            val act = when (r.outcome.name) { "VERIFIED" -> "已关闭广告"; "FAILED" -> "拦截失败"; else -> r.outcome.name }
            entries.add(ExportEntry(r.atEpochMs, r.packageName, lbl, "防护", act, "规则 ${r.ruleId} v${r.ruleVersion} · ${r.capability}"))
        }
        for (o in app.observeStore.all.value) {
            val lbl = try { pm.getApplicationInfo(o.packageName, 0).loadLabel(pm).toString() } catch (_: Exception) { o.packageName }
            val reason = if (o.reason == "miss") "无可用规则" else "点击未生效"
            entries.add(ExportEntry(o.atEpochMs, o.packageName, lbl, "未拦截", "未拦截（$reason）", o.className))
        }
        allEntries = entries.sortedByDescending { it.time }

        val grouped = allEntries.groupBy { it.pkg }
        for ((pkg, list) in grouped.entries.sortedByDescending { it.value.size }) {
            val lbl = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            val row = ItemAppBinding.inflate(layoutInflater)
            row.name.text = lbl; row.status.text = "${list.size} 条"
            val latest = list.maxOfOrNull { it.time } ?: 0L
            val kinds = list.groupBy { it.kind }.entries.joinToString(" · ") { "${it.key} ${it.value.size}" }
            row.description.text = "最近 ${fmt.format(Date(latest))} · $kinds"
            try { row.icon.setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) { }
            row.chevron.isVisible = true
            val check = CheckBox(requireContext()).apply { isChecked = true }
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            check.setOnCheckedChangeListener { _, _ -> syncSelectAll() }
            b.appList.addView(row.root)
            appChecks[pkg] = check

            val childContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(80, 0, 12, 8)
            }
            for (e in list.sortedByDescending { it.time }) {
                val cr = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(16, 12, 16, 12)
                }
                val tv = TextView(requireContext()).apply {
                    text = fmt.format(Date(e.time)); textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.sub))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val av = TextView(requireContext()).apply {
                    text = "${e.kind} · ${e.action}"; textSize = 13f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
                }
                cr.addView(tv); cr.addView(av)
                childContainer.addView(cr)
            }
            b.appList.addView(childContainer); childContainer.tag = pkg
            childContainer.isVisible = false
            row.root.setOnClickListener {
                // Set.remove 返回 Boolean（是否原本存在）——误写 != null 恒真导致永不展开
                val wasExpanded = expandedApps.remove(pkg)
                if (!wasExpanded) expandedApps.add(pkg)
                childContainer.isVisible = !wasExpanded
            }
        }

        if (grouped.isEmpty()) {
            b.appList.addView(TextView(requireContext()).apply {
                text = "暂无记录——引擎尚未看到疑似广告窗口"; setPadding(0, 40, 0, 0)
            })
        }
        b.selectAll.setOnCheckedChangeListener { _, checked -> appChecks.values.forEach { it.isChecked = checked } }

        val ranges = listOf(
            Triple("自上次导出以来", if (lastExportAtMs > 0) "上次导出于 " + fmt.format(Date(lastExportAtMs)) else "暂无上次导出，等同全部", lastExportAtMs),
            Triple("今天", "当日 00:00 起", dayStart),
            Triple("全部", "所有记录", 0L)
        )
        var checkedTime: CheckBox? = null
        for ((idx, range) in ranges.withIndex()) {
            val rangeStart = range.third
            val rangeEnd = ranges.getOrNull(idx + 1)?.third ?: Long.MAX_VALUE
            val rangeEntries = allEntries.filter { it.time >= rangeStart && it.time < rangeEnd }
            val row = ItemAppBinding.inflate(layoutInflater)
            row.name.text = "${range.first}（${rangeEntries.size} 条）"
            row.description.text = range.second
            row.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_records))
            row.chevron.isVisible = false
            row.status.isVisible = false // item 布局默认文案「待接入」对时间范围行无意义
            val check = CheckBox(requireContext())
            check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) { checkedTime?.let { if (it !== check) it.isChecked = false }; checkedTime = check }
            }
            // 初始勾选必须在 listener 就位后设置，否则不会登记进 checkedTime、互斥失效
            check.isChecked = idx == 0
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            b.timeList.addView(row.root)
            timeChecks.add(check to range.third)
            val childContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(80, 0, 12, 8)
            }
            for (e in rangeEntries.sortedByDescending { it.time }) {
                val cr = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(16, 12, 16, 12)
                }
                val tv = TextView(requireContext()).apply {
                    text = fmt.format(Date(e.time)); textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.sub))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val av = TextView(requireContext()).apply {
                    text = "${e.appName} ${e.action}"; textSize = 13f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
                }
                cr.addView(tv); cr.addView(av)
                childContainer.addView(cr)
            }
            b.timeList.addView(childContainer)
            row.root.setOnClickListener {
                childContainer.visibility = if (childContainer.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }

        b.exportBtn.setOnClickListener { doExport(app, prefs) }
    }

    private fun syncSelectAll() { b.selectAll.isChecked = appChecks.values.all { it.isChecked } }

    private fun doExport(app: top.hnwen17.guard.GuardApplication, prefs: android.content.SharedPreferences) {
        val since = if (b.appListScroll.isVisible) 0L else timeChecks.firstOrNull { it.first.isChecked }?.second ?: 0L
        val inRange: (Long) -> Boolean = { since <= 0L || it >= since }
        val chosenPkgs = appChecks.filterValues { it.isChecked }.keys
        val byPkg: (String) -> Boolean = { b.timeListScroll.isVisible || it in chosenPkgs }
        val selected = allEntries.filter { inRange(it.time) && byPkg(it.pkg) }
        if (selected.isEmpty()) {
            android.app.AlertDialog.Builder(requireContext()).setTitle("没有可选记录")
                .setMessage("所选范围内暂无记录。").setPositiveButton("知道了", null).show()
            return
        }
        val text = buildString {
            append("轻护记录导出 v${top.hnwen17.guard.BuildConfig.VERSION_NAME}（本机生成，仅供规则适配；共 ${selected.size} 条）\n")
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            for (e in selected) {
                append("\n[${fmt.format(Date(e.time))}] ${e.pkg} ${e.kind} ${e.action}")
                if (e.detail.isNotEmpty()) append("\n  detail: ${e.detail}")
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_TITLE, "轻护记录导出")
        prefs.edit().putLong("lastExportAtMs", java.lang.System.currentTimeMillis()).apply()
        startActivity(Intent.createChooser(send, "导出记录"))
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
