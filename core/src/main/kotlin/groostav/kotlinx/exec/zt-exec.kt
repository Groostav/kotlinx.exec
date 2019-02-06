package groostav.kotlinx.exec

import org.zeroturnaround.process.PidUtil
import org.zeroturnaround.process.Processes
import org.zeroturnaround.process.WindowsProcess
import java.lang.Boolean.getBoolean

/**
 * backup implementation for
 */
internal class ZeroTurnaroundProcessFacade(val process: Process, pid: Int): ProcessControlFacade {

    init {
        if(JavaVersion >= 9) trace { "WARN: using ZeroTurnaroundProcess on Java 9+" }
    }

    companion object: ProcessControlFacade.Factory  {

        val ZT_EXEC_CANT_INCLUDE_CHILDREN = Unsupported("cant include children in kill with zt-exec")

        override fun create(config: ProcessConfiguration, process: Process, pid: Int) = ifZTAvailable { ZeroTurnaroundProcessFacade(process, pid) }
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
                if(includeDescendants) { return ZT_EXEC_CANT_INCLUDE_CHILDREN }
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
                if(includeDescendants) {
                    return ZT_EXEC_CANT_INCLUDE_CHILDREN
                }
            }
        }

        pidProcess.destroyForcefully()

        return Supported(Unit)
    }
}

internal class ZeroTurnaroundPIDGenerator(): ProcessIDGenerator {

    override fun findPID(process: Process): PID = PidUtil.getPid(process)

    companion object: ProcessIDGenerator.Factory {
        override fun create() = ifZTAvailable {
            ZeroTurnaroundPIDGenerator()
        }
    }
}

private val ZTOnClassPath: Boolean by lazy { Try { Class.forName("org.zeroturnaround.process.Processes") } != null }
private val UseZeroTurnaroundIfAvailable = getBoolean("groostav.kotlinx.exec.UseZeroTurnaroundIfAvailable")

private val ZT_NOT_CONFIGURED = Unsupported("system property groostav.kotlinx.exec.UseZeroTurnaroundIfAvailable not set")
private val ZT_NOT_ON_CLASS_PATH = Unsupported("zt-process-killer & zt-exec not on classpath")

private fun <T> ifZTAvailable(lazyBuilder: () -> T): Maybe<T> = when {
    !UseZeroTurnaroundIfAvailable -> ZT_NOT_CONFIGURED
    !ZTOnClassPath -> ZT_NOT_ON_CLASS_PATH
    else -> Supported(lazyBuilder())
}