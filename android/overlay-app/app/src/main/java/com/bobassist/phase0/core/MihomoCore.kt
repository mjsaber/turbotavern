package com.bobassist.phase0.core

import android.net.VpnService
import com.bobassist.gomobile.bobcore.Bobcore
import com.bobassist.gomobile.bobcore.Protector

/**
 * Thin Kotlin facade over the gomobile-generated Bobcore class.
 *
 * All Bobcore methods return "" on success and an error message string on
 * failure (gomobile-friendly convention so we don't have to bridge Go
 * `error` across JNI).
 */
object MihomoCore {

    private class VpnProtector(private val service: VpnService) : Protector {
        override fun protect(fd: Long): Boolean = service.protect(fd.toInt())
    }

    fun version(): String = Bobcore.version()

    /**
     * MUST be called before [setup] so mihomo's first outbound dial already
     * sees a real protector. The Kotlin Protector forwards each fd to
     * VpnService.protect(fd) which marks the underlying socket to bypass
     * the VPN tunnel — required to avoid the dispatcher self-loop where
     * mihomo's DIRECT outbound would re-enter its own TUN.
     */
    fun setProtector(service: VpnService) {
        Bobcore.setProtector(VpnProtector(service))
    }

    fun setup(homeDir: String): Result<Unit> = bobcoreCall { Bobcore.setup(homeDir) }

    fun startTun(
        fd: Int,
        stack: String,
        gateway: String,
        dns: String,
    ): Result<Unit> = bobcoreCall { Bobcore.startTun(fd.toLong(), stack, gateway, dns) }

    fun stopTun(): Result<Unit> = bobcoreCall { Bobcore.stopTun() }

    private inline fun bobcoreCall(block: () -> String): Result<Unit> = runCatching {
        val err = block()
        if (err.isNotEmpty()) error(err)
    }
}
