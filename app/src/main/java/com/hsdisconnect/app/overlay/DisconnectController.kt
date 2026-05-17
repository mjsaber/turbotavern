package com.hsdisconnect.app.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DisconnectState {
    data object Idle : DisconnectState()
    data object Preparing : DisconnectState()
    data class Active(val durationMs: Long) : DisconnectState()
    data class Failed(val reason: FailureReason) : DisconnectState()
}

sealed class FailureReason {
    data object VpnNotAuthorized : FailureReason()
    data class VpnLaunchFailed(val message: String) : FailureReason()
}

interface VpnLauncher {
    fun start(durationMs: Long)
    fun stop()
}

class DisconnectController(
    private val scope: CoroutineScope,
    private val launcher: VpnLauncher,
    private val checkVpnPrepared: () -> Boolean,
) {
    private val _state = MutableStateFlow<DisconnectState>(DisconnectState.Idle)
    val state: StateFlow<DisconnectState> = _state.asStateFlow()

    private val _counter = MutableStateFlow(0)
    val counter: StateFlow<Int> = _counter.asStateFlow()

    private var activeTimer: Job? = null
    private var pendingDurationMs: Long = 0L

    fun onTap(durationMs: Long) {
        if (_state.value != DisconnectState.Idle) return
        if (!checkVpnPrepared()) {
            _state.value = DisconnectState.Failed(FailureReason.VpnNotAuthorized)
            return
        }
        _state.value = DisconnectState.Preparing
        pendingDurationMs = durationMs
        launcher.start(durationMs)
    }

    fun onVpnActive() {
        if (_state.value !is DisconnectState.Preparing) return
        _state.value = DisconnectState.Active(pendingDurationMs)
        _counter.value = _counter.value + 1
        activeTimer = scope.launch {
            delay(pendingDurationMs)
            launcher.stop()
            if (_state.value is DisconnectState.Active) {
                _state.value = DisconnectState.Idle
            }
        }
    }

    fun onVpnRevoked() {
        activeTimer?.cancel()
        if (_state.value !is DisconnectState.Idle) {
            _state.value = DisconnectState.Idle
        }
    }

    fun onVpnFailed(message: String) {
        activeTimer?.cancel()
        _state.value = DisconnectState.Failed(FailureReason.VpnLaunchFailed(message))
    }

    fun clearFailure() {
        if (_state.value is DisconnectState.Failed) _state.value = DisconnectState.Idle
    }

    fun resetCounter() {
        _counter.value = 0
    }
}
