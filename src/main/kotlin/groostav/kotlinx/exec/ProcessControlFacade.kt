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

    /**
     * a callback by which we can register completion handles.
     *
     * A base polling implementation exists in [SharedPollingResult],
     * so alternative implementations are expected to do better than polling.
     */
    // regarding funny return type,
    // I accidentally had an implementation that forgot to check the return value.
    // Because 'addCompletionHandle(handle: Handler): Maybe<Unit>' is impure,
    // its easy to forget to check that you didn't get an `Unsupported` return code.
    // by making it pure like this I made that bug into a compile-time exception.
    // it is very functional though
    val completionEvent: Maybe<ResultEventSource> get() = Unsupported


}

internal infix fun ProcessControlFacade.thenTry(backup: ProcessControlFacade): ProcessControlFacade {

    fun flatten(facade: ProcessControlFacade): List<ProcessControlFacade> = when(facade){
        is CompositeProcessFacade -> facade.facades.flatMap { flatten(it) }
        else -> listOf(facade)
    }

    return CompositeProcessFacade(flatten(this) + flatten(backup))
}

internal class CompositeProcessFacade(val facades: List<ProcessControlFacade>): ProcessControlFacade {

    init {
        require(facades.all { it !is CompositeProcessFacade } ) { "composite of composites: $this" }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> = firstSupported { it.tryKillGracefullyAsync(includeDescendants) }
    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> = firstSupported { it.killForcefullyAsync(includeDescendants) }
    override val completionEvent: Maybe<ResultEventSource>
        get() = firstSupported { it.completionEvent }

    private fun <R> firstSupported(call: (ProcessControlFacade) -> Maybe<R>): Maybe<R> {
        return facades.asSequence().map(call).firstOrNull { it != Unsupported }
                ?: throw UnsupportedOperationException("none of $facades supports $call")
    }
}

//TODO: dont like dependency on zero-turnaround, but its so well packaged...
//
// on windows: interestingly, they use a combination the cmd tools taskkill and wmic, and a reflection hack + JNA Win-Kernel32 call to manage the process
//   - note that oshi (https://github.com/oshi/oshi, EPL license) has some COM object support... why cant I just load wmi.dll from JNA?
// on linux: they dont support the deletion of children (???), and its pure shell commands (of course, since the shell is so nice)
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

internal fun makeCompositImplementation(jvmRunningProcess: Process): ProcessControlFacade {

    //TODO: look at features, reflect on runtime, maybe use a table? whats the most concise way in kotlin to express a feature map?

    return ZeroTurnaroundProcessFacade(jvmRunningProcess) thenTry ThreadBlockingResult(jvmRunningProcess)
}


