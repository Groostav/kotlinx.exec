package groostav.kotlinx.exec

import org.zeroturnaround.process.Processes
import org.zeroturnaround.process.WindowsProcess

internal class ZeroTurnaroundProcessFacade(val process: Process): ProcessControlFacade {

    val pidProcess = Processes.newPidProcess(process)

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

internal class TaskkillProcessFacade(val process: Process){

}