package groostav.kotlinx.exec

import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

internal class UnixProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    init {
        if(JavaVersion >= 9) trace { "WARN: using Unix Process Control on Java 9+" }
    }

    companion object: ProcessControlFacade.Factory {

        override fun create(config: ProcessBuilder, process: Process, pid: Int) = if (JavaProcessOS != ProcessOS.Unix) OS_NOT_UNIX else {
            Supported(UnixProcessControl(process, pid))
        }
    }

    @InternalCoroutinesApi
    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        GlobalScope.launch(Unconfined) {

            TODO("look into psmisc or pstree implementations, see if you can reach them via JNA.")
            //note, javascript's doin it!
            //https://github.com/pkrumins/node-tree-kill/blob/3b5b8feeb3175a3e16ea7e0e09fdf5b8d2b87b08/index.js#L41

//            val pids = when {
//                includeDescendants -> {
//                    val (pids, _) = exec("pgrep", "-P", "$pid")
//                    pids.map { it.toInt() } + pid
//                }
//                else -> listOf(pid)
//            }

            execAsync("kill", "$pid").consumeEach { trace { it.formattedMessage } }
        }

        return Supported(Unit)
    }

    @InternalCoroutinesApi
    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
        //can we issue a pgrep -P call here
//        if(includeDescendants) return Unsupported
        TODO("look into psmisc or pstree implementations, see if you can reach them via JNA.")

        GlobalScope.launch(Unconfined) {
            execAsync { command = listOf("kill", "-9", "$pid") }.consumeEach { trace { it.formattedMessage } }
        }

        return Supported(Unit)
    }
}

internal class UnixReflectivePIDGen: ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create() = if(JavaProcessOS == ProcessOS.Unix){
            Supported(UnixReflectivePIDGen())
        }
        else OS_NOT_UNIX
    }

    val field = Class.forName("java.lang.UNIXProcess")
            .getDeclaredField("pid")
            .apply { isAccessible = true }

    override fun findPID(process: Process): PID = field.getInt(process)
}

private val OS_NOT_UNIX = Unsupported("OS is not unix")