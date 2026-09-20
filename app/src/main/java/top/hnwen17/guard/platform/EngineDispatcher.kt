package top.hnwen17.guard.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import top.hnwen17.guard.core.engine.GuardEvent
import top.hnwen17.guard.core.session.WindowSession
import java.util.concurrent.ConcurrentHashMap

/**
 * 有界事件调度器（QH-P04-08）。
 *
 * 结构：
 * - 一个规则工作协程（单消费者，规则计算天然串行）；
 * - 普通 Channel 容量 [CAPACITY]（DROP_OLDEST）：洪泛时丢最旧事件，事件可再生成；
 * - 控制 Channel 容量 [CONTROL_CAPACITY]（DROP_OLDEST 但 select 优先消费）：STOP_ALL/PAUSE 永不排队等业务；
 * - 同会话窗口事件合并：工作器消费时只保留每会话最新一条（[drainCoalesced] 由 reduce 循环调用）；
 * - epoch 取消：会话切换时在途任务统一作废。
 *
 * 线程约束：全部规则计算发生在注入的 [workerContext]，Main 只做 submit（非阻塞入队）。
 */
class EngineDispatcher(
    private val scope: CoroutineScope,
    private val workerContext: kotlin.coroutines.CoroutineContext,
    private val onEvent: suspend (GuardEvent) -> Unit
) {

    private val controlChannel = Channel<GuardEvent.Control>(CONTROL_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val normalChannel = Channel<GuardEvent>(CAPACITY, BufferOverflow.DROP_OLDEST)
    private val inflight = ConcurrentHashMap<Long, Job>()
    private val latestWindowEvent = HashMap<Long, GuardEvent>()

    private var worker: Job? = null

    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch(workerContext) {
            while (true) {
                // 控制优先：select 在两通道同时就绪时偏向控制通道
                val event = select<GuardEvent> {
                    controlChannel.onReceive { it }
                    normalChannel.onReceive { it }
                }
                try {
                    // QH-P08 实测加固：单事件异常不杀死事件循环（否则服务静默失聪）
                    drainCoalesced().forEach { onEvent(it) }
                    if (event is GuardEvent.Control) applyControl(event) else onEvent(event)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable：Errors（如深树SO/OOM）同样不得杀死循环
                    android.util.Log.e("RuleRuntime", "event handler error", e)
                }
            }
        }
    }

    private fun applyControl(control: GuardEvent.Control) {
        when (control.command) {
            GuardEvent.Control.Command.STOP_ALL -> {
                inflight.values.forEach { it.cancel() }
                inflight.clear()
                synchronized(latestWindowEvent) { latestWindowEvent.clear() }
            }
            GuardEvent.Control.Command.PAUSE, GuardEvent.Control.Command.RESUME -> Unit
        }
    }

    /**
     * 入队（廉价、非阻塞、永不抛出）：
     * - 控制事件 → 控制通道；
     * - 窗口事件 → 同会话合并槽覆盖旧值（容量压力时最旧会话先淘汰）+ 通道信号；
     * - 其余事件 → 普通通道。
     */
    fun submit(event: GuardEvent) {
        if (event is GuardEvent.Control) {
            controlChannel.trySend(event)
            return
        }
        if (event is GuardEvent.WindowChanged) {
            val key = sessionKey(event.session)
            synchronized(latestWindowEvent) {
                if (latestWindowEvent.size >= CAPACITY) {
                    latestWindowEvent.keys.minByOrNull { it }?.let { if (it != key) latestWindowEvent.remove(it) }
                }
                latestWindowEvent[key] = event
            }
            normalChannel.trySend(event)
            return
        }
        normalChannel.trySend(event)
    }

    /** 冲刷合并槽：同会话窗口事件只计算最新一次。 */
    fun drainCoalesced(): List<GuardEvent> = synchronized(latestWindowEvent) {
        val events = latestWindowEvent.values.toList()
        latestWindowEvent.clear()
        events
    }

    /** 注册在途任务：epoch 变化时由 [cancelSession] 统一作废。 */
    fun track(session: WindowSession, job: Job) {
        inflight[session.epoch] = job
        job.invokeOnCompletion { inflight.remove(session.epoch, job) }
    }

    fun cancelSession(session: WindowSession) {
        inflight.remove(session.epoch)?.cancel()
        synchronized(latestWindowEvent) {
            latestWindowEvent.entries.removeAll {
                it.value.session.sameWindow(session) && it.value.session.epoch != session.epoch
            }
        }
    }

    fun stopAll() {
        submit(
            GuardEvent.Control(
                session = WindowSession(0, "", 0, 0),
                atMonotonicMs = System.nanoTime() / 1_000_000L,
                command = GuardEvent.Control.Command.STOP_ALL
            )
        )
    }

    fun shutdown() {
        worker?.cancel()
        controlChannel.close()
        normalChannel.close()
    }

    private fun sessionKey(session: WindowSession): Long {
        var result = session.userId.toLong()
        result = 31 * result + session.packageName.hashCode().toLong()
        result = 31 * result + session.windowId
        return result
    }

    companion object {
        const val CAPACITY = 64
        const val CONTROL_CAPACITY = 8
    }
}

/** 规则计算统一离开 Main 线程的显式包装（供服务端调用）。 */
suspend fun <T> offMain(context: kotlin.coroutines.CoroutineContext, block: suspend () -> T): T =
    withContext(context) { block() }
