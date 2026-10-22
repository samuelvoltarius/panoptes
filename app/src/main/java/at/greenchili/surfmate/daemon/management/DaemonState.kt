package at.greenchili.surfmate.daemon.management

import at.greenchili.surfmate.config.DaemonType

/**
 * Represents the current state of a daemon.
 */
data class DaemonState(
    val type: DaemonType,
    val running: Boolean,
    val pid: Int? = null,
    val startTime: Long? = null,
    val autoStart: Boolean = false
)
