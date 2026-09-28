package top.hnwen17.guard.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hnwen17.guard.R
import top.hnwen17.guard.core.Availability
import top.hnwen17.guard.data.rules.UserRuleStore
import top.hnwen17.guard.databinding.FragmentUserRulesBinding
import top.hnwen17.guard.databinding.ItemAppBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的规则：用户自建跳过规则的管理与可视化创建（零代码）。
 *
 * 创建流程（三步对话框）：选最近的未拦截记录 → 选跳过按钮证据（样本或手输）
 * → 选作用范围（默认仅此 App）。生成规则自动带安全护栏并即时生效。
 */
class UserRulesFragment : Fragment() {

    private var _b: FragmentUserRulesBinding? = null
    private val b get() = _b!!
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentUserRulesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.createBtn.setOnClickListener { pickObservation() }
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun app() = requireContext().applicationContext as top.hnwen17.guard.GuardApplication

    private fun appName(pkg: String): String = try {
        requireContext().packageManager.getApplicationInfo(pkg, 0).loadLabel(requireContext().packageManager).toString()
    } catch (_: Exception) { pkg }

    private fun render() {
        val ctx = requireContext()
        b.ruleList.removeAllViews()
        val rules = app().userRuleStore.all.value.sortedByDescending { it.createdAt }
        if (rules.isEmpty()) {
            b.ruleList.addView(TextView(ctx).apply {
                text = "还没有自建规则。\n\n遇到没被跳过的广告：打开产生广告的 App 让它再出现一次，然后回到这里点下方按钮，按提示两步就能创建。"
                setPadding(0, 40, 0, 0)
                setTextColor(ContextCompat.getColor(ctx, R.color.sub))
            })
            return
        }
        val records = app().recordStore.all.value
        for (r in rules) {
            val row = ItemAppBinding.inflate(layoutInflater)
            row.name.text = r.buttonText
            row.status.text = if (r.enabled) "启用中" else "已停用"
            row.status.badge(if (r.enabled) Availability.ACTIVE else Availability.UNCONNECTED)
            val scopeText = if (r.packageName == "*") "所有应用" else appName(r.packageName)
            val hits = records.count { it.ruleId == r.id }
            row.description.text = "范围：$scopeText · 命中 $hits 次 · 创建于 ${fmt.format(Date(r.createdAt))}"
            try { row.icon.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_check)) } catch (_: Exception) { }
            row.chevron.isVisible = false
            val sw = android.widget.Switch(ctx).apply {
                isChecked = r.enabled
                contentDescription = "启用或停用此规则"
                setOnCheckedChangeListener { _, checked ->
                    app().userRuleStore.setEnabled(r.id, checked)
                    UserRuleStore.save(ctx, app().userRuleStore)
                    app().ruleRuntime.reload(ctx)
                    render()
                }
            }
            row.root.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.root.setOnClickListener {
                android.app.AlertDialog.Builder(ctx).setTitle("删除规则")
                    .setMessage("删除「${r.buttonText}」？删除后立即生效，随时可以重新创建。")
                    .setPositiveButton("删除") { _, _ ->
                        app().userRuleStore.remove(r.id)
                        top.hnwen17.guard.data.rules.UserRuleStore.save(ctx, app().userRuleStore)
                        app().ruleRuntime.reload(ctx)
                        render()
                    }
                    .setNegativeButton("取消", null).show()
            }
            b.ruleList.addView(row.root)
        }
    }

    // ── 创建向导 · 第 1 步：选最近的未拦截记录 ──
    private fun pickObservation() {
        val ctx = requireContext()
        val obs = app().observeStore.all.value.sortedByDescending { it.atEpochMs }.take(20)
        if (obs.isEmpty()) {
            Toast.makeText(ctx, "暂无未拦截记录——遇到没跳过的广告后，回到这里创建", Toast.LENGTH_LONG).show()
            return
        }
        val labels = obs.map { o ->
            val first = o.samples.firstOrNull { it.isNotBlank() }?.take(24) ?: o.className.take(24)
            "${appName(o.packageName)} · ${fmt.format(Date(o.atEpochMs))} · $first"
        }
        android.app.AlertDialog.Builder(ctx).setTitle("选择出问题的广告（最近 20 条）")
            .setItems(labels.toTypedArray()) { _, which -> chooseEvidence(obs[which]) }
            .setNegativeButton("取消", null).show()
    }

    // ── 第 2 步：选跳过按钮的证据 ──
    private fun chooseEvidence(o: top.hnwen17.guard.data.records.ObserveStore.Observation) {
        val ctx = requireContext()
        val samples = o.samples.filter { it.isNotBlank() }.distinct()
        val MANUAL = "✍️ 手动输入按钮上的文字"
        val items = (samples + MANUAL).toTypedArray()
        android.app.AlertDialog.Builder(ctx).setTitle("广告上「跳过」按钮的文字是？")
            .setItems(items) { _, which ->
                if (which == samples.size) {
                    val input = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = "例如：跳过" }
                    android.app.AlertDialog.Builder(ctx).setTitle("输入按钮文字")
                        .setView(input)
                        .setPositiveButton("确定") { _, _ ->
                            val t = input.text.toString().trim()
                            if (t.isEmpty()) Toast.makeText(ctx, "文字为空，已取消", Toast.LENGTH_SHORT).show()
                            else chooseScope(o, t, "TEXT")
                        }
                        .setNegativeButton("取消", null).show()
                } else {
                    val raw = samples[which]
                    when {
                        raw.startsWith("#") -> chooseScope(o, raw.substringAfter("#").trim(), "VIEW_ID")
                        raw.startsWith("@") -> chooseScope(o, raw.substringAfter("@").trim(), "DESC")
                        else -> chooseScope(o, raw.trim(), "TEXT")
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ── 第 3 步：选作用范围并生成 ──
    private fun chooseScope(o: top.hnwen17.guard.data.records.ObserveStore.Observation, evidence: String, kind: String) {
        val ctx = requireContext()
        if (kind == "TEXT" && top.hnwen17.guard.data.rules.UserRuleStore.isRiskyCommonWord(evidence)) {
            Toast.makeText(ctx, "「$evidence」是常见按钮文字，建议只在本应用内生效", Toast.LENGTH_LONG).show()
        }
        val scopes = arrayOf("仅此应用：${appName(o.packageName)}（推荐，最安全）", "所有应用")
        android.app.AlertDialog.Builder(ctx).setTitle("规则在哪些应用生效？")
            .setSingleChoiceItems(scopes, 0) { dialog, which ->
                dialog.dismiss()
                val pkg = if (which == 0) o.packageName else "*"
                app().userRuleStore.add(pkg, evidence, kind, o.className)
                top.hnwen17.guard.data.rules.UserRuleStore.save(ctx, app().userRuleStore)
                app().ruleRuntime.reload(ctx)
                render()
                Toast.makeText(ctx, "规则已创建并生效，下次遇到同款广告将自动跳过", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
