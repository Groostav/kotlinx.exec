package groostav.kotlinx.exec

import kotlin.streams.asSequence

internal class JEP102ProcessFacade(val process: Process) : ProcessControlFacade {

    val procHandle = process.toHandle()

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = supportedIf(JavaVersion >= 9) { JEP102ProcessFacade(process) }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean{

            if( ! handle.supportsNormalTermination()) return false

            //recurse on children
            val childSequence = handle.children().asSequence().takeUnless { ! includeChildren }
            val successfulInfanticide = childSequence == null || childSequence.fold(true){ accum, next ->
                //this is really morbid...
                accum && killRecursor(next, includeChildren)
            }

            if ( ! successfulInfanticide) return false

            val destroyed = handle.destroy()
            trace { "JEP102 destroy [gracefully] PID=${handle.pid()}: ${if(destroyed)"success" else "FAILED"}" }

            // assume success, once we've killed one process there isnt much point in trying to stop
            // we probably got false because it exited naturally
            return true
        }

        val success = killRecursor(procHandle, includeDescendants)
        return if(success) Supported(Unit) else Unsupported
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean{

            //recurse on children
            val childSequence = handle.children().asSequence().takeUnless { ! includeChildren }
            val successfulInfanticide = childSequence == null || childSequence.fold(true){ accum, next ->
                accum && killRecursor(next, includeChildren)
            }

            if ( ! successfulInfanticide) return false

            val destroyed = handle.destroyForcibly()
            trace { "JEP102 destroy forcibly PID=${handle.pid()}: ${if(destroyed)"success" else "FAILED"}" }

            // assume success, same as above.
            return true
        }

        val success = killRecursor(procHandle, includeDescendants)
        return if(success) Supported(Unit) else Unsupported
    }
}

internal class JEP102ProcessIDGenerator(private val process: Process): ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create(process: Process) = supportedIf(JavaVersion >= 9) { JEP102ProcessIDGenerator(process) }
    }

    override val pid: Maybe<Int> = Supported(process.pid().toInt())

}