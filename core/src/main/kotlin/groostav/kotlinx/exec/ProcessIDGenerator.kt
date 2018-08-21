package groostav.kotlinx.exec

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import org.zeroturnaround.process.PidUtil

internal interface ProcessIDGenerator {
    /**
     * The OS-relevant process ID integer.
     */
    val pid: Maybe<Int>

    interface Factory {
        fun create(process: Process): Maybe<ProcessIDGenerator>
    }
}

internal fun makePIDGenerator(jvmRunningProcess: Process): ProcessIDGenerator{
    val factories = listOf(
            JEP102ProcessIDGenerator,
            WindowsReflectiveNativePIDGen, UnixReflectivePIDGen,
            ZeroTurnaroundPIDGenerator
    )

    return factories.firstSupporting { it.create(jvmRunningProcess) }
}
