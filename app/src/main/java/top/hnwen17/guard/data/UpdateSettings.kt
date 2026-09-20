package top.hnwen17.guard.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 在线更新设置（QH-P16，用户授权 2026-09-18）。
 *
 * 产品决策（ADR-003）：
 * - 默认**关闭**：应用安装后无任何自发网络请求；
 * - 用户显式开启并提供来源 URL 后才拉取；拉取内容必须过 ECDSA 验签+严格解析才入库；
 * - 关闭时 INTERNET 权限无实质流量（无后台轮询/无遥测）。
 */
class UpdateSettings(context: Context, scope: CoroutineScope) {

    private val application = context.applicationContext
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        application.preferencesDataStoreFile("guard-update")
    }
    private val enabledKey = booleanPreferencesKey("online_update_enabled")
    private val urlKey = stringPreferencesKey("online_update_url")

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val mutations = Channel<suspend () -> Unit>(capacity = 8)

    init {
        scope.launch {
            for (task in mutations) try { task() } catch (_: Exception) { }
        }
        scope.launch {
            dataStore.data.collect { prefs ->
                _enabled.value = prefs[enabledKey] ?: false
                _url.value = prefs[urlKey] ?: ""
            }
        }
    }

    fun setEnabled(enabled: Boolean, url: String) {
        mutations.trySend {
            dataStore.edit {
                it[enabledKey] = enabled
                it[urlKey] = if (enabled) url else ""
            }
        }
    }
}
