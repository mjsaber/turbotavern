package com.hsdisconnect.app.overlay

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisconnectControllerTest {

    private fun controller(
        launcher: VpnLauncher = mockk(relaxed = true),
        prepareOk: () -> Boolean = { true },
    ): Pair<DisconnectController, TestDispatcher> {
        val dispatcher = StandardTestDispatcher()
        return DisconnectController(
            scope = TestScope(dispatcher),
            launcher = launcher,
            checkVpnPrepared = prepareOk,
        ) to dispatcher
    }

    @Test
    fun `initial state is Idle and counter is 0`() = runTest {
        val (c, _) = controller()
        assertEquals(DisconnectState.Idle, c.state.value)
        assertEquals(0, c.counter.value)
    }

    @Test
    fun `tap when Idle and prepared transitions to Active and increments counter`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher)
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        // Simulate launcher reporting active
        c.onVpnActive()
        assertEquals(DisconnectState.Active(5_000L), c.state.value)
        assertEquals(1, c.counter.value)
        verify { launcher.start(5_000L) }
    }

    @Test
    fun `tap when VPN not prepared goes to Failed without counter increment`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher, prepareOk = { false })
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        assertEquals(DisconnectState.Failed(FailureReason.VpnNotAuthorized), c.state.value)
        assertEquals(0, c.counter.value)
        verify(exactly = 0) { launcher.start(any()) }
    }

    @Test
    fun `Active times out to Idle after duration`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher)
        c.onTap(3_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        dispatcher.scheduler.advanceTimeBy(3_100L)
        assertEquals(DisconnectState.Idle, c.state.value)
        verify { launcher.stop() }
    }

    @Test
    fun `onVpnRevoked from Active returns to Idle`() = runTest {
        val (c, dispatcher) = controller()
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        c.onVpnRevoked()
        assertEquals(DisconnectState.Idle, c.state.value)
    }

    @Test
    fun `tap while not Idle is ignored`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher)
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        c.onTap(5_000L)  // second tap during Active
        dispatcher.scheduler.runCurrent()
        assertEquals(1, c.counter.value)
        verify(exactly = 1) { launcher.start(any()) }
    }

    @Test
    fun `resetCounter sets counter to 0`() = runTest {
        val (c, dispatcher) = controller()
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        c.resetCounter()
        assertEquals(0, c.counter.value)
    }

    @Test
    fun `vpn launch failure transitions to Failed`() = runTest {
        val (c, dispatcher) = controller()
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnFailed("establish returned null")
        assertEquals(
            DisconnectState.Failed(FailureReason.VpnLaunchFailed("establish returned null")),
            c.state.value
        )
        assertEquals(0, c.counter.value)
    }
}
