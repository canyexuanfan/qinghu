package top.hnwen17.guard.data

import org.json.JSONArray
import org.json.JSONObject
import top.hnwen17.guard.core.*

/**
 * 设置编解码（QH-P04-03）。
 *
 * schema 2：新增应用级三态（tri）与临时允许（temporaryAllow，能力→到期epoch ms）。
 * 兼容读取 schema 1：旧 enabled 集合按 PolicyResolver.fromLegacy 迁移为 ON/OFF，不丢用户意图；
 * 无 tri 字段的应用 = 全 INHERIT。未知能力名丢弃；损坏字段回退默认值。
 */
internal object SettingsCodec {
    private const val SCHEMA = 2

    fun encode(settings: Settings): String = JSONObject().apply {
        put("schema", SCHEMA); put("paused", settings.paused)
        put("enabled", JSONArray(settings.enabled.map { it.name }))
        put("autoUpdate", settings.autoUpdate); put("reduceMotion", settings.reduceMotion)
        put("temporaryAllow", JSONObject().apply {
            settings.temporaryAllow.forEach { (cap, expiry) -> put(cap.name, expiry) }
        })
        put("policies", JSONObject().apply {
            settings.policies.forEach { (id, policy) ->
                put(id, JSONObject().apply {
                    put("enabled", JSONArray(policy.enabled.map { it.name }))
                    put("strength", policy.strength.name)
                    if (policy.tri.isNotEmpty()) put("tri", JSONObject().apply {
                        policy.tri.forEach { (cap, tri) -> put(cap.name, tri.name) }
                    })
                })
            }
        })
    }.toString()

    fun decode(raw: String?): Settings {
        if (raw == null) return Settings()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return Settings()
        val policies = obj.optJSONObject("policies") ?: JSONObject()
        val temporary = obj.optJSONObject("temporaryAllow")
        val now = System.currentTimeMillis()
        return Settings(
            obj.optBoolean("paused", false), caps(obj.optJSONArray("enabled")),
            obj.optBoolean("autoUpdate", true), obj.optBoolean("reduceMotion", true),
            policies.keys().asSequence().take(1000).associateWith { id ->
                val p = policies.optJSONObject(id) ?: JSONObject()
                AppPolicy(caps(p.optJSONArray("enabled")),
                    runCatching { Strength.valueOf(p.optString("strength", "SMART")) }.getOrDefault(Strength.SMART),
                    tri(p.optJSONObject("tri")))
            },
            // 临时允许：过期项在解码时直接丢弃（有界例外，不复活）
            temporary?.let { json ->
                json.keys().asSequence().mapNotNull { name ->
                    val cap = runCatching { Capability.valueOf(name) }.getOrNull() ?: return@mapNotNull null
                    val expiry = json.optLong(name, 0L)
                    if (expiry > now) cap to expiry else null
                }.toMap()
            } ?: emptyMap()
        )
    }

    private fun tri(json: JSONObject?): Map<Capability, TriPolicy> {
        json ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { name ->
            val cap = runCatching { Capability.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { TriPolicy.valueOf(json.optString(name, "INHERIT")) }.getOrDefault(TriPolicy.INHERIT)
            cap to value
        }.toMap()
    }

    private fun caps(array: JSONArray?): Set<Capability> {
        if (array == null) return Capability.entries.toSet()
        return (0 until array.length()).mapNotNull { i -> runCatching { Capability.valueOf(array.optString(i)) }.getOrNull() }.toSet()
    }
}
