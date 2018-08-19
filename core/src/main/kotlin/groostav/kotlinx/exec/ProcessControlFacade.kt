package groostav.kotlinx.exec

internal interface ProcessControlFacade {

    /**
     * attempts to kill the process via the SIG_INT mechanism
     *
     * Notes:
     * - method should return in a timely **non-blocking** fashion,
     * - it is not expected that when this function returns the process is dead
     * - as per the nature of SIG_INT, it is not guaranteed that upon successful signally,
     *   the process will ever end
     */
    fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> = Unsupported

    /**
     * kills the process via SIG_KILL mechanisms.
     *
     * Notes:
     * - method should return in a timely **no-blocking** fashion,
     * - by nature of `kill -9` and "end task", this process is expected to kill the child process eventually
     */
    fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> = Unsupported


    interface Factory {
        fun create(process: Process, pid: Int): Maybe<ProcessControlFacade>
    }

}

internal class CompositProcessControl(val facades: List<ProcessControlFacade>): ProcessControlFacade {

    init {
        require(facades.all { it !is CompositProcessControl } ) { "composite of composites: $this" }
        require(facades.any()) { "composite has no implementations!" }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean) = Supported(facades.firstSupporting {
        it.tryKillGracefullyAsync(includeDescendants)
    })
    override fun killForcefullyAsync(includeDescendants: Boolean) = Supported(facades.firstSupporting {
        it.killForcefullyAsync(includeDescendants)
    })
}

internal fun makeCompositeFacade(jvmRunningProcess: Process, pid: Int): ProcessControlFacade {
    val factories = listOf(
            JEP102ProcessFacade,
            WindowsProcessControl,
            UnixProcessControl,
            ZeroTurnaroundProcessFacade
    )
    val facades = factories.filterSupporting { it.create(jvmRunningProcess, pid) }
    return CompositProcessControl(facades)
}
