package top.hnwen17.guard.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import top.hnwen17.guard.R
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import top.hnwen17.guard.core.*
import top.hnwen17.guard.databinding.*
import java.time.*

class AppsAdapter(private val icons: AppIconLoader, private val scope: CoroutineScope,
                  private val open: (String) -> Unit, private val sort: (View) -> Unit) : ListAdapter<AppRow, RecyclerView.ViewHolder>(DIFF) {
    private class Header(val b: ItemSectionBinding) : RecyclerView.ViewHolder(b.root)
    private class Item(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) { var iconJob: Job? = null }
    override fun getItemViewType(position: Int) = if (getItem(position) is AppRow.Header) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (type == 0) Header(ItemSectionBinding.inflate(inflater, parent, false)) else Item(ItemAppBinding.inflate(inflater, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is AppRow.Header -> (holder as Header).run {
                b.title.text = row.title
                b.sortAction.isVisible = row.sortable
                b.sortAction.setOnClickListener { sort(it) }
            }
            is AppRow.Item -> (holder as Item).run {
                b.name.text = row.app.name
                val basic = row.app.icon in setOf("chat", "wallet", "map", "music", "mail", "book", "sliders")
                b.description.text = if (row.policy.enabled.isEmpty() || basic) "基础保护" else
                    listOf(Capability.CLEANER, Capability.SENSOR, Capability.TOUCH, Capability.JUMP)
                        .filter { it in row.policy.enabled }.take(3).joinToString(" · ") {
                            when(it) { Capability.CLEANER -> "广告净化"; Capability.TOUCH -> "防误触"; Capability.SENSOR -> "防摇一摇"; Capability.JUMP -> "跳转保护" }
                        }
                b.root.setBackgroundResource(when(row.groupPosition) {
                    1 -> R.drawable.bg_group_top; 2 -> R.drawable.bg_group_middle
                    3 -> R.drawable.bg_group_bottom; else -> R.drawable.bg_ripple_card
                })
                b.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = if (row.groupPosition == -1) (8 * b.root.resources.displayMetrics.density).toInt() else 0
                }
                b.status.badge(row.status, if (row.status == Availability.ACTIVE) "已保护" else statusLabel(row.status))
                iconJob?.cancel(); iconJob = icons.bind(b.icon, row.app, scope)
                b.root.setOnClickListener { open(row.app.id) }
            }
        }
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is Item) { holder.iconJob?.cancel(); holder.b.icon.setImageDrawable(null) }
    }
    companion object {
        val DIFF = object: DiffUtil.ItemCallback<AppRow>() {
            override fun areItemsTheSame(a: AppRow, b: AppRow) = when { a is AppRow.Header && b is AppRow.Header -> a.title == b.title; a is AppRow.Item && b is AppRow.Item -> a.app.id == b.app.id; else -> false }
            override fun areContentsTheSame(a: AppRow, b: AppRow) = a == b
        }
    }
}
sealed interface RecordRow {
    data class Header(val day: LocalDate): RecordRow
    data class Item(val record: ProtectionRecord): RecordRow
}
fun timeline(records: List<ProtectionRecord>): List<RecordRow> = buildList {
    var last: LocalDate? = null
    for (record in records) {
        val day = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        if (day != last) { add(RecordRow.Header(day)); last = day }
        add(RecordRow.Item(record))
    }
}
class RecordsAdapter(private val onRecordClick: (top.hnwen17.guard.core.ProtectionRecord) -> Unit = { }) : ListAdapter<RecordRow, RecyclerView.ViewHolder>(DIFF) {
    private class Header(val b: ItemSectionBinding): RecyclerView.ViewHolder(b.root)
    private class Item(val b: ItemRecordBinding): RecyclerView.ViewHolder(b.root)
    override fun getItemViewType(position: Int) = if (getItem(position) is RecordRow.Header) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (type == 0) Header(ItemSectionBinding.inflate(inflater, parent, false)) else Item(ItemRecordBinding.inflate(inflater, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when(val row = getItem(position)) {
            is RecordRow.Header -> (holder as Header).b.title.text = when(row.day) {
                LocalDate.now() -> "今天"; LocalDate.now().minusDays(1) -> "昨天"; else -> row.day.toString()
            }
            is RecordRow.Item -> {
                (holder as Item).b.root.setOnClickListener { onRecordClick(row.record) }
                showRecord(holder.b, row.record)
            }
        }
    }
    companion object {
        val DIFF = object: DiffUtil.ItemCallback<RecordRow>() {
            override fun areItemsTheSame(a: RecordRow, b: RecordRow) = when { a is RecordRow.Header && b is RecordRow.Header -> a.day == b.day; a is RecordRow.Item && b is RecordRow.Item -> a.record.id == b.record.id; else -> false }
            override fun areContentsTheSame(a: RecordRow, b: RecordRow) = a == b
        }
    }
}
