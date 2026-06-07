package com.bobassist.phase0.devrec

import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression for the drag crash: DragListener must call updateViewLayout on the ROOT (the WM-managed
 * row), not on the touched ≡ handle child. Passing the child sets WindowManager.LayoutParams on it,
 * which makes the parent LinearLayout's measure cast blow up:
 *   ClassCastException: WindowManager$LayoutParams cannot be cast to LinearLayout$LayoutParams
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class MarkerPanelDragTest {

    private fun rootOf(panel: MarkerPanel): LinearLayout {
        val f = MarkerPanel::class.java.getDeclaredField("view").apply { isAccessible = true }
        return f.get(panel) as LinearLayout
    }

    private fun motion(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    @Test
    fun dragging_the_handle_does_not_corrupt_child_params_or_crash_measure() {
        val panel = MarkerPanel(RuntimeEnvironment.getApplication(), onMark = {}, onStop = {})
        panel.show()
        val root = rootOf(panel)
        val handle = root.getChildAt(0)              // the ≡ TextView carries the drag listener

        // Simulate a drag on the handle: press then move.
        handle.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f))
        handle.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, 160f, 140f))

        // The bug set WindowManager.LayoutParams on the child; the fix leaves it a normal child param.
        assertFalse(
            "handle child must not receive WindowManager.LayoutParams",
            handle.layoutParams is WindowManager.LayoutParams,
        )
        assertTrue(
            "root (WM-managed) keeps WindowManager.LayoutParams",
            root.layoutParams is WindowManager.LayoutParams,
        )

        // With a corrupted child param this throws ClassCastException inside LinearLayout.measure.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }
}
