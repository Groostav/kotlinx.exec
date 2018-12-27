package groostav.kotlinx.exec

import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch


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

internal class UnixReflectivePIDGen(private val process: Process): ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create(process: Process) = supportedIf(JavaProcessOS == ProcessOS.Unix){
            UnixReflectivePIDGen(process)
        }
    }

    private val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }

    override val pid: Supported<Int> = Supported(field.getInt(process))
}
