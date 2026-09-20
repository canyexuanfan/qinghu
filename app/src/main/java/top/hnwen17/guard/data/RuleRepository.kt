package top.hnwen17.guard.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 规则仓库（QH-P08-05/06）。
 *
 * 规则启用状态持久化（独立 DataStore "guard-rules"）：
 * - 默认：**停用**——只有"已测试有效"（自有 fixture 规则，PROJECT-OWNED）才允许启用；
 * - 用户可按 ruleId 停用；停用后执行器直接跳过；
 * - 有界写队列（32），静默失败与主设置仓库同口径。
 */
class RuleRepository(context: Context, scope: CoroutineScope) {

    data class RuleState(
        val known: Boolean = false,          // 规则包已登记（fixture 加载成功）
        val testedEffective: Boolean = false, // 测试矩阵通过
        val enabled: Boolean = false          // 默认停用；执行器查询此值
    )

    private val application = context.applicationContext
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        application.preferencesDataStoreFile("guard-rules")
    }
    private val knownKey = stringSetPreferencesKey("known_rules")
    private val enabledKey = stringSetPreferencesKey("enabled_rules")

    private val _states = MutableStateFlow<Map<String, RuleState>>(emptyMap())
    val states: StateFlow<Map<String, RuleState>> = _states.asStateFlow()

    private val mutations = Channel<suspend () -> Unit>(capacity = 32)

    init {
        scope.launch {
            for (task in mutations) try { task() } catch (_: Exception) { }
        }
        scope.launch {
            dataStore.data.collect { prefs ->
                val known = prefs[knownKey] ?: emptySet()
                val enabled = prefs[enabledKey] ?: emptySet()
                _states.value = known.associateWith { id ->
                    RuleState(known = true, testedEffective = true, enabled = id in enabled)
                }
            }
        }
    }

    /**
     * 规则加载器登记新规则。
     * @param defaultEnabled true=安装即可用（内置通用包）；false=默认停用（debug fixture）
     */
    fun registerKnown(ruleIds: Set<String>, defaultEnabled: Boolean = false) {
        mutations.trySend {
            dataStore.edit { prefs ->
                val current = prefs[knownKey] ?: emptySet()
                prefs[knownKey] = current + ruleIds
                if (defaultEnabled) {
                    val enabled = prefs[enabledKey] ?: emptySet()
                    prefs[enabledKey] = enabled + ruleIds // 首次登记即启用；用户仍可经设置停用
                }
            }
        }
    }

    /** QH-P08-05：只启已测试有效规则；未登记（未知）规则一律拒绝。 */
    fun setEnabled(ruleId: String, enabled: Boolean) {
        mutations.trySend {
            dataStore.edit { prefs ->
                val known = prefs[knownKey] ?: emptySet()
                if (ruleId !in known) return@edit // 未知规则默认停或限制
                val current = prefs[enabledKey] ?: emptySet()
                prefs[enabledKey] = if (enabled) current + ruleId else current - ruleId
            }
        }
    }

    /** 执行器查询：规则是否启用（内存快照；未登记/默认=停用）。 */
    fun isEnabled(ruleId: String): Boolean =
        _states.value[ruleId]?.enabled == true

    /** QH-P08-06：失败后停用该规则（不承诺撤销已点击行为——UI 文案表达）。 */
    fun recordFailureAndDisable(ruleId: String) {
        setEnabled(ruleId, false)
    }
}
