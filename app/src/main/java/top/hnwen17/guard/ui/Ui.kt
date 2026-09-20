package top.hnwen17.guard.ui

import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import top.hnwen17.guard.R
import top.hnwen17.guard.core.*
import top.hnwen17.guard.databinding.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val recordClock = DateTimeFormatter.ofPattern("HH:mm")
fun ImageView.capability(cap: Capability) {
    imageTintList = null
    setBackgroundResource(0)
    setPadding(0, 0, 0, 0)
    setImageResource(when (cap) {
        Capability.CLEANER -> R.drawable.cap_cleaner
        Capability.TOUCH -> R.drawable.cap_touch
        Capability.SENSOR -> R.drawable.cap_sensor
        Capability.JUMP -> R.drawable.cap_jump
    })
}
fun TextView.badge(status: Availability, label: String = statusLabel(status)) {
    text = label
    val (color, bg) = when (status) {
        Availability.ACTIVE -> R.color.green_dark to R.drawable.bg_mint
        Availability.LIMITED, Availability.NEEDS_PERMISSION, Availability.ERROR -> R.color.orange to R.drawable.bg_orange
        else -> R.color.sub to R.drawable.bg_gray
    }
    setTextColor(ContextCompat.getColor(context, color)); setBackgroundResource(bg)
}
fun ItemCapabilityBinding.bind(cap: Capability, state: Availability, onClick: () -> Unit) {
    icon.capability(cap)
    title.text = when (cap) {
        Capability.CLEANER -> "广告净化"; Capability.TOUCH -> "防误触"
        Capability.SENSOR -> "防摇一摇"; Capability.JUMP -> "跳转保护"
    }
    description.text = cap.description
    status.badge(state)
    root.setOnClickListener { onClick() }
}
fun ItemPolicyBinding.bind(cap: Capability, checked: Boolean, onChange: (Boolean) -> Unit) {
    icon.capability(cap); title.text = cap.title; description.text = cap.description
    toggle.contentDescription = "${cap.title}策略"
    toggle.setOnCheckedChangeListener(null); toggle.isChecked = checked
    toggle.setOnCheckedChangeListener { _, enabled -> onChange(enabled) }
}
fun ItemSettingBinding.bind(titleText: String, subtitleText: String, valueText: String, image: Int,
                           status: Availability = Availability.UNCONNECTED,
                           showChevron: Boolean = true, onClick: () -> Unit) {
    title.text = titleText; subtitle.text = subtitleText; subtitle.isVisible = subtitleText.isNotEmpty()
    value.badge(status, valueText); value.isVisible = valueText.isNotEmpty()
    icon.imageTintList = null; icon.setImageResource(image)
    chevron.isVisible = showChevron
    root.setOnClickListener { onClick() }
}
private fun titleFor(record: ProtectionRecord) = when (record.capability) {
    Capability.CLEANER ->
        if (record.outcome == Outcome.OBSERVED) "拦截失败" else "已关闭广告"
    Capability.TOUCH -> "防止广告误触"
    Capability.SENSOR -> if (record.sample) "防止摇一摇广告" else "防摇一摇保护"
    Capability.JUMP -> if (record.outcome == Outcome.START_REJECTED) "阻止异常跳转" else "异常跳转观察"
}
private fun showRecordDetail(view: android.view.View, record: ProtectionRecord) {
    val date = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault())
    AlertDialog.Builder(view.context).setTitle(titleFor(record))
        .setMessage("${record.appName}\n${date.toLocalDate()} ${date.format(recordClock)}\n\n${record.detail}\n\n" +
            if (record.sample) "这是原生预览构建的示例记录，没有执行真实拦截。" else "这是防护数据源提供的实际记录。")
        .setPositiveButton("知道了", null).show()
}
fun showRecord(binding: ItemRecordBinding, record: ProtectionRecord) {
    val date = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault())
    binding.time.text = date.format(recordClock)
    binding.icon.capability(record.capability)
    binding.title.text = titleFor(record)
    val label = if (!record.sample) record.outcome.label else when(record.outcome) {
        Outcome.CLOSED -> "已处理"; Outcome.TOUCH_BLOCKED -> "已拦截"
        Outcome.SENSOR_APPLIED, Outcome.START_REJECTED -> "已阻止"; Outcome.OBSERVED -> "拦截失败"
    }
    binding.status.badge(if (record.outcome == Outcome.OBSERVED) Availability.LIMITED else Availability.ACTIVE, label)
    if (record.outcome == Outcome.TOUCH_BLOCKED) {
        binding.status.setTextColor(ContextCompat.getColor(binding.root.context, R.color.blue))
        binding.status.setBackgroundResource(R.drawable.bg_blue)
    }
    binding.description.text = "${record.appName} ${record.detail}"
    binding.root.setOnClickListener { showRecordDetail(it, record) }
}
fun showRecent(binding: ItemRecentBinding, record: ProtectionRecord) {
    binding.time.text = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault()).format(recordClock)
    binding.icon.capability(record.capability)
    binding.title.text = titleFor(record)
    binding.description.text = "${record.appName} ${record.detail}"
    binding.root.setOnClickListener { showRecordDetail(it, record) }
}
