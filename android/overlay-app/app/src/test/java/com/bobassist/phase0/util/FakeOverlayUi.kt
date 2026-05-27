package com.bobassist.phase0.util

import com.bobassist.phase0.overlay.OverlayState
import com.bobassist.phase0.overlay.OverlayUi

class FakeOverlayUi : OverlayUi {
    @Volatile var visible: Boolean = false
        private set
    @Volatile var lastState: OverlayState = OverlayState.WaitingForBattle
        private set
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override fun show() { visible = true; log += "show" }
    override fun hide() { visible = false; log += "hide" }
    override fun setVisible(v: Boolean) { visible = v; log += "setVisible($v)" }
    override fun applyState(s: OverlayState) { lastState = s; log += "applyState($s)" }
    override fun onConfigurationChanged() { log += "onConfigurationChanged" }
}
