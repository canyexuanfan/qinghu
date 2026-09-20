package top.hnwen17.guard.platform.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QH-P09-02/03：边界解析与手势状态机（纯逻辑单测）。 */
class ShieldTest {

    @Test
    fun `边界与可见区取交集并排除IME`() {
        val node = ShieldBoundsResolver.Rect(100, 400, 600, 900)
        val window = ShieldBoundsResolver.Rect(0, 0, 1080, 1920)
        val r = ShieldBoundsResolver.resolve(node, window, imeTop = null, session = S, densityDpi = 440)!!
        assertEquals(100, r.rect.left); assertEquals(900, r.rect.bottom)
        // IME 顶在 700：裁掉下半
        val r2 = ShieldBoundsResolver.resolve(node, window, imeTop = 700, session = S, densityDpi = 440)!!
        assertEquals(700, r2.rect.bottom)
    }

    @Test
    fun `过小或屏外区域拒绝建盾`() {
        val tiny = ShieldBoundsResolver.Rect(0, 0, 20, 20)
        assertNull(ShieldBoundsResolver.resolve(tiny, W, null, S, 440))
        val offscreen = ShieldBoundsResolver.Rect(2000, 100, 2400, 400)
        assertNull(ShieldBoundsResolver.resolve(offscreen, W, null, S, 440))
    }

    @Test
    fun `手势完整消费且解除推迟到手势终止`() {
        val state = ShieldTouchState()
        state.onDown(1)
        assertEquals(ShieldTouchState.Phase.GESTURE_ACTIVE, state.phase)
        assertTrue(state.requestDismiss())
        assertEquals(ShieldTouchState.Phase.PENDING_DISMISS, state.phase)
        // 手势未结束：不移除
        assertFalse(state.onUpOrCancel(allPointersUp = false))
        // 全部抬起：移除
        assertTrue(state.onUpOrCancel(allPointersUp = true))
        assertEquals(ShieldTouchState.Phase.IDLE, state.phase)
    }

    @Test
    fun `多指一致策略-全部抬起才结算`() {
        val state = ShieldTouchState()
        state.onDown(1)
        state.onPointerDown(2)
        assertEquals(2, state.pointers)
        state.onPointerUp(1)
        // 未请求解除时手势结束：盾保留（IDLE），不移除
        assertFalse(state.onUpOrCancel(allPointersUp = true))
        state.onDown(2)
        state.requestDismiss()
        // 请求解除后手势终止：移除
        assertTrue(state.onUpOrCancel(allPointersUp = true))
    }

    @Test
    fun `重置直接清空（窗口切换场景）`() {
        val state = ShieldTouchState()
        state.onDown(1); state.requestDismiss()
        state.reset()
        assertEquals(ShieldTouchState.Phase.IDLE, state.phase)
        assertEquals(0, state.pointers)
    }

    companion object {
        private val S = top.hnwen17.guard.core.session.WindowSession(0, "pkg", 1, epoch = 1)
        private val W = ShieldBoundsResolver.Rect(0, 0, 1080, 1920)
    }
}
