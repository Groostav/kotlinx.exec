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
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean) = Supported(facades.firstSupporting {
        it.tryKillGracefullyAsync(includeDescendants)
    })
    override fun killForcefullyAsync(includeDescendants: Boolean) = Supported(facades.firstSupporting {
        it.killForcefullyAsync(includeDescendants)
    })
}

//TODO: dont like dependency on zero-turnaround, but its so well packaged...
//
// on windows: interestingly, they use a combination the cmd tools taskkill and wmic,
// and a reflection hack + JNA Win-Kernel32 call to manage the process
//   - note that oshi (https://github.com/oshi/oshi, EPL license) has some COM object support...
// why cant I just load wmi.dll from JNA?
// on linux: they dont support the deletion of children (???),
// and its pure shell commands (of course, since the shell is so nice)
// what about android or even IOS? *nix == BSD support means... what? is there even a use-case here?
//
// so I think cross-platform support is a little trickey,
// - java-9 supports some of these features themselves, namely `onComplete` (via `CompletableFuture`) and PID.
//    - see http://www.baeldung.com/java-9-process-api
// - each OS provides a pretty good mechanism for killing parent processes, kill process-trees might be more involved.
// - dont know who would want this library on android or IOS, so we might skip compatibility there.
// - despite lack of android support, targeting JDK-6 is still probably a good idea.
// - in order to run processes like `ls` to avoid a recursive dependency,
//   I might need an `internal fun exec0(cmd): List<String?)`, similar to zero-turnaround.

internal fun makeCompositeFacade(jvmRunningProcess: Process, pid: Int): ProcessControlFacade {
    val factories = listOf(
            JEP102ProcessFacade,
            WindowsProcessControl,
            ZeroTurnaroundProcessFacade
    )
    val facades = factories
            .map { it.create(jvmRunningProcess, pid) }
            .filterIsInstance<Supported<ProcessControlFacade>>()
            .map { it.value }

    return CompositProcessControl(facades)
}

internal fun makePIDGenerator(jvmRunningProcess: Process): ProcessIDGenerator{
    val factories = listOf(
            JEP102ProcessIDGenerator,
            ReflectiveNativePIDGen
    )

    return factories.firstSupporting { it.create(jvmRunningProcess) }
}

internal fun makeListenerProvider(jvmRunningProcess: Process, pid: Int, config: ProcessBuilder): ProcessListenerProvider {
    return PollingListenerProvider(jvmRunningProcess, pid, config)
}

private fun <R, S> List<S>.firstSupporting(call: (S) -> Maybe<R>): R {
    val candidate = this.asSequence().map(call).filterIsInstance<Supported<R>>().firstOrNull()
            ?: throw UnsupportedOperationException("none of $this supports $call")

    return candidate.value
}