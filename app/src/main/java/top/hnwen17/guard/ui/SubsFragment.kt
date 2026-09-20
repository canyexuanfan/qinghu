package top.hnwen17.guard.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hnwen17.guard.R
import top.hnwen17.guard.databinding.FragmentSubsBinding
import top.hnwen17.guard.databinding.ItemAppBinding
import top.hnwen17.guard.data.SubscriptionsStore

/**
 * 阶段3：规则订阅页（底部第 5 个 tab）。
 * 三级状态：停用（零加载）/ 仅观察（RECORD_ONLY，默认）/ 启用（按原动作点击）。
 * 内置规则集开关独立；新添加订阅默认仅观察。
 */
class SubsFragment : BoundFragment<FragmentSubsBinding>(FragmentSubsBinding::inflate) {

    private val store get() = (requireActivity().application as top.hnwen17.guard.GuardApplication).subscriptionsStore

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.builtinSwitch.setOnCheckedChangeListener(null)
        binding.builtinSwitch.isChecked = store.builtinEnabled.value
        binding.builtinSwitch.setOnCheckedChangeListener { _, checked ->
            store.setBuiltinEnabled(checked)
        }
        binding.externalSwitch.setOnCheckedChangeListener { _, checked ->
            store.setExternalEnabled(checked)
        }
        observe(store.all) { subs -> renderSubs(subs.filter { it.id != "builtin" }) }
        observe(store.externalEnabled) { ext -> binding.externalSwitch.isChecked = ext }
        binding.addBtn.setOnClickListener { showAddDialog() }
    }

    private fun renderSubs(subs: List<SubscriptionsStore.SubEntry>) {
        binding.subList.removeAllViews()
        binding.empty.isVisible = subs.isEmpty()
        for (sub in subs.sortedByDescending { it.lastUpdateAtMs }) {
            val row = ItemAppBinding.inflate(layoutInflater)
            row.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_globe))
            row.name.text = sub.name
            val enabled = sub.state == SubscriptionsStore.STATE_ENABLED
            row.status.text = when (sub.state) {
                SubscriptionsStore.STATE_ENABLED -> "已启用"
                SubscriptionsStore.STATE_OBSERVE -> "仅观察"
                else -> "已停用"
            }
            val domain = try { java.net.URI(sub.url).host ?: "" } catch (_: Exception) { "" }
            row.description.text = buildString {
                append("${sub.ruleCount} 条 · $domain")
                if (sub.license.isNotEmpty()) append(" · ${sub.license}")
                if (sub.lastUpdateAtMs > 0) append(" · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(sub.lastUpdateAtMs))}")
            }
            row.chevron.isVisible = false
            row.root.setOnClickListener { showSubDialog(sub) }
            binding.subList.addView(row.root)
        }
    }

    private fun showSubDialog(sub: SubscriptionsStore.SubEntry) {
        val options = arrayOf("停用", "仅观察（推荐先用）", "启用（真实点击）", "重新拉取", "删除订阅")
        android.app.AlertDialog.Builder(requireContext()).setTitle(sub.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> store.upsert(sub.copy(state = SubscriptionsStore.STATE_DISABLED))
                    1 -> store.upsert(sub.copy(state = SubscriptionsStore.STATE_OBSERVE))
                    2 -> store.upsert(sub.copy(state = SubscriptionsStore.STATE_ENABLED))
                    3 -> pull(sub.url)
                    4 -> { store.remove(sub.id); deletePackFile(sub) }
                }
            }.show()
    }

    private fun deletePackFile(sub: SubscriptionsStore.SubEntry) {
        sub.fileName?.let { fn ->
            try { requireContext().getFileStreamPath("subscriptions/$fn").delete() } catch (_: Exception) { }
            try { java.io.File(requireContext().getExternalFilesDir(null), "subscriptions/$fn").delete() } catch (_: Exception) { }
        }
    }

    private fun showAddDialog() {
        val input = EditText(requireContext()).apply { setSingleLine(); hint = "https://…（轻护规则包或李跳跳规则 JSON）" }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input) }
        android.app.AlertDialog.Builder(requireContext()).setTitle("添加订阅")
            .setView(box)
            .setMessage("仅接受 https 链接。新订阅默认「仅观察」：命中只记录不点击，可在记录页确认后再启用。")
            .setPositiveButton("添加") { _, _ ->
                val url = input.text.toString().trim()
                when {
                    !url.startsWith("https://") -> explain("仅支持 https 链接")
                    else -> pull(url)
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun pull(url: String) {
        // 阶段4 多源回退：推荐订阅按 RuleSources 顺序尝试，第一个成功的源生效
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val urls = if (url == top.hnwen17.guard.data.SubscriptionsStore.RECOMMENDED_URL)
                top.hnwen17.guard.platform.update.RuleSources.packUrls(
                    top.hnwen17.guard.platform.update.RuleSources.RECOMMENDED_PACK)
            else listOf(url)
            var lastMsg = ""
            for (candidate in urls) {
                val r = withContext(Dispatchers.IO) { top.hnwen17.guard.data.SubscriptionImporter.fetch(ctx, candidate) }
                if (r.ok) { applyResult(ctx, url, r); return@launch }
                lastMsg = r.message
            }
            explain("所有订阅源均不可达。${lastMsg}内置规则仍正常工作。")
        }
    }

    private fun applyResult(ctx: android.content.Context, url: String, r: top.hnwen17.guard.data.SubscriptionImporter.Result) {
        if (!r.ok) { explain("拉取失败：" + r.message); return }
        if (r.packJson != null && r.fileName != null) {
            val dir = java.io.File(ctx.filesDir, "subscriptions")
            dir.mkdirs()
            java.io.File(dir, r.fileName).writeText(r.packJson)
        }
        store.upsert(top.hnwen17.guard.data.SubscriptionsStore.SubEntry(
            id = r.packId, name = r.name, url = url,
            state = SubscriptionsStore.STATE_OBSERVE, lastUpdateAtMs = System.currentTimeMillis(),
            ruleCount = r.ruleCount, license = r.license, fileName = r.fileName
        ))
        android.app.AlertDialog.Builder(ctx).setTitle("订阅已更新")
            .setMessage("来源：$url\n收下 ${r.ruleCount} 条（默认仅观察，零点击）\n\n确认记录页证据后，可在订阅条目中切换为「启用」。")
            .setPositiveButton("知道了", null).show()
    }

    private fun explain(msg: String) {
        android.app.AlertDialog.Builder(requireContext()).setMessage(msg).setPositiveButton("知道了", null).show()
    }
}
