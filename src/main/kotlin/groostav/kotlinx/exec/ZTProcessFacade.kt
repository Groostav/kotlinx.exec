package groostav.kotlinx.exec

import org.zeroturnaround.process.Processes
import org.zeroturnaround.process.WindowsProcess

//TODO: what do i need this for anymore?
// we're putting JNA on the path for 1.8 => kern32 style PID getter is handled
// also we copied their impl for kill and kill with children
// waitFor... and its WMIC magic... is superfluous?
//      Whats the reason to actually ping the OS? AFAIK there is none?
// ==> Delete this, and the dep on zt!
internal class ZeroTurnaroundProcessFacade(val process: Process, pid: Int): ProcessControlFacade {

    init {
        if(JavaVersion >= 9) trace { "WARN: using ZeroTurnaroundProcess on Java 9+" }
    }

    companion object: ProcessControlFacade.Factory  {
        val ztOnClassPath: Boolean = Try { Class.forName("org.zeroturnaround.process.Processes") } != null
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
}