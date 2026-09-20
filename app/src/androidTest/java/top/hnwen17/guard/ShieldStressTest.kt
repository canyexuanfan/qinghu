package top.hnwen17.guard

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import top.hnwen17.guard.core.session.WindowSession
import top.hnwen17.guard.platform.touch.ShieldBoundsResolver
import top.hnwen17.guard.platform.touch.TouchShieldManager
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QH-P09-10：重复建撤压测（不依赖 a11y 事件投递，直接驱动 WindowManager）。
 * 通过标准：1000 轮无异常完成；前后 Debug 内存快照供人工比对（不伪造无泄漏结论）。
 */
@RunWith(AndroidJUnit4::class)
class ShieldStressTest {

    @Test
    fun thousandCyclesShowDismiss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wm = context.getSystemService(WindowManager::class.java)
        val rect = ShieldBoundsResolver.Rect(100, 200, 700, 600)
        val session = WindowSession(0, "stress", 1, epoch = 1)
        val memBefore = android.os.Debug.getNativeHeapAllocatedSize()

        var cycles = 0
        try {
            for (i in 0 until 1000) {
                instrumentation.runOnMainSync {
                    val state = top.hnwen17.guard.platform.touch.ShieldTouchState()
                    val view = top.hnwen17.guard.platform.touch.ShieldView(context, state, rect) { }
                    val params = WindowManager.LayoutParams().apply {
                        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        width = rect.width; height = rect.height
                    }
                    wm.addView(view, params)
                    wm.removeView(view)
                }
                cycles++
            }
        } finally {
            val memAfter = android.os.Debug.getNativeHeapAllocatedSize()
            android.util.Log.i(
                "ShieldStress",
                "cycles=$cycles nativeBefore=$memBefore nativeAfter=$memAfter delta=${memAfter - memBefore}"
            )
        }
        org.junit.Assert.assertEquals(1000, cycles)
    }
}
