package groostav.kotlinx.exec

import org.zeroturnaround.process.Processes
import org.zeroturnaround.process.WindowsProcess

internal class ZeroTurnaroundProcessFacade(val process: Process, pid: Int): ProcessControlFacade {

    init {
        if(JavaVersion >= 9) trace { "WARN: using ZeroTurnaroundProcess on Java 9+" }
    }

    companion object: ProcessControlFacade.Factory  {
        val ztOnClassPath: Boolean = Class.forName("org.zeroturnaround.process.Processes") != null
        override fun create(process: Process, pid: Int) = supportedIf(ztOnClassPath) { ZeroTurnaroundProcessFacade(process, pid) }
    }

    private val pidProcess = Processes.newPidProcess(pid)

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        when(pidProcess){
            is WindowsProcess -> {
                pidProcess.isGracefulDestroyEnabled = true
                pidProcess.isIncludeChildren = includeDescendants
            }
            else -> {
                //can we simply issue a pgrep -P call here?
                if(includeDescendants) { return Unsupported }
            }
        }

        pidProcess.destroyGracefully()

        return Supported(Unit)
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        when(pidProcess){
            is WindowsProcess -> {
                pidProcess.isIncludeChildren = includeDescendants
            }
            else -> {
                if(includeDescendants) { return Unsupported }
            }
        }

        pidProcess.destroyForcefully()

        return Supported(Unit)
    }

    override val completionEvent: Maybe<ResultEventSource>
        get() = Unsupported
}