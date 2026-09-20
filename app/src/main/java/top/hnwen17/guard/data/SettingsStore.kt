package top.hnwen17.guard.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 局部允许设置存储（QH-P09-07）。
 *
 * 合同：暂时解除绑定会话/时间（有界到期）；**长期关闭需用户明确操作**；
 * 一次解除绝不演变成全局禁用。
 */
class SettingsStore(context: Context, scope: CoroutineScope) {

    private val application = context.applicationContext
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        application.preferencesDataStoreFile("guard-shield")
    }
    private val dismissedKey = longPreferencesKey("dismissed_until")
    private val longTermOffKey = booleanPreferencesKey("shield_long_term_off")

    private val _shieldDismissedUntil = MutableStateFlow(0L)
    val shieldDismissedUntil: StateFlow<Long> = _shieldDismissedUntil.asStateFlow()

    private val _shieldLongTermOff = MutableStateFlow(false)
    val shieldLongTermOff: StateFlow<Boolean> = _shieldLongTermOff.asStateFlow()

    private val mutations = Channel<suspend () -> Unit>(capacity = 16)

    init {
        scope.launch {
            for (task in mutations) try { task() } catch (_: Exception) { }
        }
        scope.launch {
            dataStore.data.collect { prefs ->
                _shieldDismissedUntil.value = prefs[dismissedKey] ?: 0L
                _shieldLongTermOff.value = prefs[longTermOffKey] ?: false
            }
        }
    }

    /** 暂时解除：绑定时限（epoch ms），到期自动恢复。 */
    fun dismissTemporarily(untilEpochMs: Long) {
        mutations.trySend {
            dataStore.edit {
                it[dismissedKey] = untilEpochMs
                it[longTermOffKey] = false // 暂时解除不改变长期开关
            }
        }
    }

    /** 长期关闭：仅用户明确操作可设置（UI 需二次确认文案）。 */
    fun setLongTermOff(off: Boolean) {
        mutations.trySend {
            dataStore.edit {
                it[longTermOffKey] = off
                if (off) it[dismissedKey] = 0L
            }
        }
    }

    /** 护罩是否应显示：未长期关闭且不在暂时解除期。 */
    fun shouldShow(nowEpochMs: Long): Boolean =
        !_shieldLongTermOff.value && nowEpochMs >= _shieldDismissedUntil.value
}
