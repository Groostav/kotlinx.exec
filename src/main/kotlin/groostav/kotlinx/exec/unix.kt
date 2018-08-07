package groostav.kotlinx.exec

import com.sun.jna.Platform


internal class UnixProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    init {
        if(JavaVersion >= 9) trace { "WARN: using Unix Process Control on Java 9+" }
    }

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = supportedIf(Platform.isWindows()) { WindowsProcessControl(process, pid) }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Supported<Unit> {
        //can we issue a pgrep -P call here
        TODO()
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Supported<Unit> {
        TODO()
    }
}

internal class UnixReflectivePIDGen(private val process: Process): ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create(process: Process) = supportedIf(JavaProcessOS == ProcessOS.Unix){
            WindowsReflectiveNativePIDGen(process)
        }
    }

    private val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }

    override val pid: Supported<Int> = Supported(field.getInt(process))
}
