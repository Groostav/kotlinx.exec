package groostav.kotlinx.exec

import kotlin.reflect.KFunction
import kotlin.streams.asSequence

internal class JEP102ProcessFacade(val process: Process) : ProcessControlFacade {

    val procHandle = process.toHandle()

    companion object: ProcessControlFacade.Factory {
        val CANT_NORMAL_TERMINATION_FOR = { pid: Long ->
            Unsupported("ProcessHandle does not support normal termination of pid=$pid on platform=$JavaProcessOS")
        }
        val DESTROY_FAILED_FOR = { pid: Long ->
            Unsupported("ProcessHandle.destroy failed for pid=$pid")
        }

        override fun create(config: ProcessConfiguration, process: Process, pid: Int) =
                if(JavaVersion >= 9) Supported(JEP102ProcessFacade(process)) else NOT_JAVA_9
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Maybe<Unit> {

            if( ! handle.supportsNormalTermination()) return CANT_NORMAL_TERMINATION_FOR(handle.pid())

            //recurse on children
            if(includeChildren){
                val childrenDead = handle.children().asSequence().fold(SupportedUnit as Maybe<Unit>){ accum, next ->
                    if(accum is Supported) killRecursor(next, includeChildren) else accum
                }

                if (childrenDead !is Supported) return childrenDead
            }

            val destroyed = handle.destroy()
            trace { "JEP102 destroy [gracefully] PID=${handle.pid()}: $destroyed}" }

            // assume success, once we've killed one process there isnt much point in trying to stop
            // we probably got false because it exited naturally
            return if(destroyed) SupportedUnit else DESTROY_FAILED_FOR(handle.pid())
        }

        return killRecursor(procHandle, includeDescendants)
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Maybe<Unit> {

            //recurse on children
            if(includeChildren){
                val childrenDead = handle.children().asSequence().fold(SupportedUnit as Maybe<Unit>){ accum, next ->
                    if(accum is Supported) killRecursor(next, includeChildren) else accum
                }

                if (childrenDead !is Supported) return childrenDead
            }

            val destroyed = handle.destroyForcibly()
            trace { "JEP102 destroy forcibly PID=${handle.pid()}: ${if(destroyed)"success" else "FAILED"}" }

            // assume success, same as above.
            return if(destroyed) SupportedUnit else DESTROY_FAILED_FOR(handle.pid())
        }

        return killRecursor(procHandle, includeDescendants)
    }
}

internal class JEP102ProcessIDGenerator(): ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create() = if(JavaVersion >= 9) { Supported(JEP102ProcessIDGenerator()) } else NOT_JAVA_9
    }

    override fun findPID(process: Process): PID = process.pid().toInt()

}

private val NOT_JAVA_9 = Unsupported("needed java-9 or higher (is ${System.getProperty("java.version")})")