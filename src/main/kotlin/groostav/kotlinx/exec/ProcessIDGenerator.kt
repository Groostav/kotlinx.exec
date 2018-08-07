package groostav.kotlinx.exec

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import org.zeroturnaround.process.PidUtil

internal interface ProcessIDGenerator {
    /**
     * The OS-relevant process ID integer.
     */
    //TODO whats the expected behaviour if the process exited?
    val pid: Maybe<Int> get() = Unsupported

    interface Factory {
        fun create(process: Process): Maybe<ProcessIDGenerator>
    }
}

internal fun makePIDGenerator(jvmRunningProcess: Process): ProcessIDGenerator{
    val factories = listOf(
            JEP102ProcessIDGenerator,
            ReflectiveNativePIDGen
    )

    return factories.firstSupporting { it.create(jvmRunningProcess) }
}

internal class ReflectiveNativePIDGen(private val process: Process): ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create(process: Process) = supportedIf(JavaVersion < 9){ ReflectiveNativePIDGen(process) }
    }

    override val pid: Supported<Int> by lazy {

        val processType = process::class.java

        val pid = when(processType.name) {
            "java.lang.ProcessImpl" -> {

                //TODO: bleh... so what if i restrict myself to only whats available in jna.Platform?
                // it seems pretty powerful, and there must be a way to get all features out of the OS exposed APIs,
                // rather than relying on command line tools like taskkill and wmic and ps and etc.
                // further, to my mind, its much more elegant.
                // and then: does java-9 completely alleviate this? _mostly_ alleviate this?

                val field = processType.getDeclaredField("handle").apply {
                    isAccessible = true
                }
                val handlePeer = field.getLong(process)

                val handle = WinNT.HANDLE().apply { pointer = Pointer.createConstant(handlePeer) }
                Kernel32.INSTANCE.GetProcessId(handle)
            }
            else -> {
                PidUtil.getPid(process) //TODO, get me on linux!
            }
        }

        Supported(pid)
    }
}

