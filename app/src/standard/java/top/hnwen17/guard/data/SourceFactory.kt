package top.hnwen17.guard.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import top.hnwen17.guard.core.*
import top.hnwen17.guard.platform.CapabilityRepository

object SourceFactory {
    fun create(): FrontendSource = object : FrontendSource {
        override val preview = false

        // QH-P05-04/QH-P08：四能力全部经由无障碍 Binder 通道实现（BACK防御/规则点击/遮罩）。
        // Shizuku 仅为系统级增强第二道防线（P10/P12 可选），不阻塞主能力状态。
        override val availability: Flow<Map<Capability, Availability>> = combine(
            CapabilityRepository.accessibilityState,
            CapabilityRepository.shizukuState
        ) { a11y, _ ->
            android.util.Log.d("GuardFlow", "availability a11y=${a11y.availability}")
            mapOf(
                Capability.CLEANER to a11y.availability,
                Capability.TOUCH to a11y.availability,
                Capability.SENSOR to a11y.availability,
                Capability.JUMP to a11y.availability
            )
        }
        override val records = emptyList<ProtectionRecord>()
        override suspend fun loadApplications(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                val packageName = info.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val flags = info.applicationInfo?.flags ?: 0
                val system = flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                AppEntry(packageName, result.loadLabel(pm).toString(), "installed", system = system)
            }.distinctBy { it.id }.sortedBy { it.name }
        }
    }
}
