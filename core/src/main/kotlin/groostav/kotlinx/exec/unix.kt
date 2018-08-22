package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch


internal class UnixProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    init {
        if(JavaVersion >= 9) trace { "WARN: using Unix Process Control on Java 9+" }
    }

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = supportedIf(JavaProcessOS == ProcessOS.Unix) {
            UnixProcessControl(process, pid)
        }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
        //can we issue a pgrep -P call here
//        if(includeDescendants) return Unsupported

        val x = 4;

        launch(Unconfined) {
            val (out, r) = exec { command = listOf("pgrep", "-P", "$pid") }

            println(out.joinToString("\n"))
            val y = 4;

            execAsync { command = listOf("kill", "$pid") }.consumeEach { trace { it.formattedMessage } }
        }

        return Supported(Unit)
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
        //can we issue a pgrep -P call here
//        if(includeDescendants) return Unsupported

        launch(Unconfined) {
            execAsync { command = listOf("kill", "-9", "$pid") }.consumeEach { trace { it.formattedMessage } }
        }

        return Supported(Unit)
    }
}

internal class UnixReflectivePIDGen(private val process: Process): ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create(process: Process) = supportedIf(JavaProcessOS == ProcessOS.Unix){
            UnixReflectivePIDGen(process)
        }
    }

    private val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }

    override val pid: Supported<Int> = Supported(field.getInt(process))
}
