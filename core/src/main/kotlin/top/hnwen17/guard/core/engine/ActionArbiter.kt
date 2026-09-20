package top.hnwen17.guard.core.engine

import top.hnwen17.guard.core.Capability

/**
 * 动作仲裁器（QH-P04-07）。
 *
 * 职责：把 Reducer 给出的候选动作序列裁决为"实际可执行序列"：
 * - STOP_ALL / PAUSE 优先于一切新动作：暂停后不得继续点击；
 * - 同一会话内 CLEANER 关闭与 TOUCH 遮罩互斥（先关闭成功则撤销遮罩，避免残盾）；
 * - 敏感应用/敏感流程一律不产生主动点击类动作；
 * - 同类动作按优先级去重（保留优先级最高的一条），避免洪泛执行。
 */
object ActionArbiter {

    /** 能力动作优先级：数值越小越先执行。 */
    private fun priority(action: ProtectionReducer.CandidateAction): Int = when (action) {
        is ProtectionReducer.CandidateAction.CloseAd -> 0
        is ProtectionReducer.CandidateAction.BlockLaunch -> 1
        is ProtectionReducer.CandidateAction.MaskRegion -> 2
        is ProtectionReducer.CandidateAction.StartSensorSession -> 3
        is ProtectionReducer.CandidateAction.RecordOnly -> 9
    }

    /** 裁决输入：候选动作 + 当前执行端状态。 */
    data class ExecutionContext(
        val paused: Boolean,
        val stopRequested: Boolean,
        val sensitive: Boolean,
        val sensorGateOpen: Boolean
    )

    /**
     * @return 实际应执行的动作（保持稳定顺序），被否决的动作不产生副作用。
     */
    fun arbitrate(
        candidates: List<ProtectionReducer.CandidateAction>,
        context: ExecutionContext
    ): List<ProtectionReducer.CandidateAction> {
        // 安全停止：一切新动作（含记录类）全部丢弃，停止优先
        if (context.stopRequested) return emptyList()
        // 暂停：不产生任何主动执行类动作；仅保留纯记录（不落"已执行"成绩）
        val allowed = if (context.paused) {
            candidates.filterIsInstance<ProtectionReducer.CandidateAction.RecordOnly>()
        } else {
            candidates
        }

        // 敏感应用：不产生点击/遮罩/传感器/拦截类动作
        val nonSensitive = if (context.sensitive) {
            allowed.filterIsInstance<ProtectionReducer.CandidateAction.RecordOnly>()
        } else {
            allowed
        }

        // Sensor 安全门：门未开（NO_GO/PENDING）不得启动传感器会话
        val gateFiltered = nonSensitive.filter {
            it !is ProtectionReducer.CandidateAction.StartSensorSession || context.sensorGateOpen
        }

        // 去重：同类型动作只保留优先级最高（最具体）的一条
        val deduped = gateFiltered
            .groupBy { it::class }
            .map { group -> group.value.minBy { priority(it) } }

        // 互斥：关闭与遮罩不同窗执行——先关闭，遮罩撤销（防残盾）
        val hasClose = deduped.any { it is ProtectionReducer.CandidateAction.CloseAd }
        val ordered = deduped
            .filter { !(hasClose && it is ProtectionReducer.CandidateAction.MaskRegion) }
            .sortedBy { priority(it) }
        return ordered
    }

    /**
     * 关闭成功后的收尾：返回需要撤销的动作（如遮罩），保证"关闭成功后不残盾"。
     * 执行端在收到关闭 VERIFIED 后调用。
     */
    fun onCloseVerified(activeMasks: Collection<ProtectionReducer.CandidateAction>): List<ProtectionReducer.CandidateAction> =
        activeMasks.filterIsInstance<ProtectionReducer.CandidateAction.MaskRegion>().map { it }
}
