package top.hnwen17.guard.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import top.hnwen17.guard.core.*

/** View data boundary. A connected backend must supply confirmed states, not UI switch values. */
interface FrontendSource {
    val preview: Boolean
    val availability: Flow<Map<Capability, Availability>>
    val records: List<ProtectionRecord>
    val defaultSettings: Settings get() = Settings()
    val summaryToday: Summary? get() = null
    val appRecordCounts: Map<String, Int> get() = emptyMap()
    suspend fun loadApplications(context: Context): List<AppEntry>
    fun previewScenario(scenario: Int) = Unit
}
