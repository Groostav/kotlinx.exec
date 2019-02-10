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
    fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit>

    /**
     * kills the process via SIG_KILL mechanisms.
     *
     * Notes:
     * - method should return in a timely **no-blocking** fashion,
     * - by nature of `kill -9` and "end task", this process is expected to kill the child process eventually
     */
    fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit>


    interface Factory {
        fun create(config: ProcessConfiguration, process: Process, pid: Int): Maybe<ProcessControlFacade>
    }

}

internal class CompositeProcessControl(val facades: List<Maybe<ProcessControlFacade>>): ProcessControlFacade {

    init {
        require(facades.all { it.valueOrNull !is CompositeProcessControl } ) { "composite of composites: $this" }
        require(facades.any()) { "composite has no implementations!" }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Supported<Unit> {
        return Supported(facades.asSequence().map {
            it.supporting(ProcessControlFacade::tryKillGracefullyAsync, includeDescendants)
        }.firstSupported())
    }
    override fun killForcefullyAsync(includeDescendants: Boolean): Supported<Unit> {
        return Supported(facades.asSequence().map {
            it.supporting(ProcessControlFacade::killForcefullyAsync, includeDescendants)
        }.firstSupported())
    }

    override fun toString() = "CompositeProcessControl[${facades.joinToString()}]"
}

internal class CompositeProcessControlFactory(val factories: List<ProcessControlFacade.Factory>): ProcessControlFacade.Factory {

    companion object: ProcessControlFacade.Factory by CompositeProcessControlFactory(listOf(
            JEP102ProcessFacade,
            WindowsProcessControl,
            UnixProcessControl,
            ZeroTurnaroundProcessFacade
    ))

    override fun create(config: ProcessConfiguration, process: Process, pid: Int): Maybe<ProcessControlFacade> {
        val facades = factories.map {
            Supported(it).supporting(ProcessControlFacade.Factory::create, config, process, pid)
        }
        return Supported(CompositeProcessControl(facades))

    }
}