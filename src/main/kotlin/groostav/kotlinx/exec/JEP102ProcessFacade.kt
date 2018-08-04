package groostav.kotlinx.exec

import kotlin.streams.asSequence

internal class JEP102ProcessFacade(val process: Process) : ProcessControlFacade {

    init { require(isAvailable) }

    val procHandle = process.toHandle()

    companion object: ProcessControlFacade.Factory {
        override val isAvailable = JavaVersion >= 9
        override fun create(process: Process, pid: Int) = JEP102ProcessFacade(process)
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean{

            if( ! handle.supportsNormalTermination()) return false

            //recurse on children
            val successfulInfanticide = includeChildren && handle.children().asSequence().fold(true){ accum, next ->
                accum && killRecursor(next, includeChildren)
            }

            if ( ! successfulInfanticide) return false

            val destroyed = handle.destroy()

            return destroyed
        }

        val success = killRecursor(procHandle, includeDescendants)
        return if(success) Supported(Unit) else Unsupported
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean{

            //recurse on children
            val successfulInfanticide = includeChildren && handle.children().asSequence().fold(true){ accum, next ->
                accum && killRecursor(next, includeChildren)
            }

            if ( ! successfulInfanticide) return false

            val destroyed = handle.destroyForcibly()

            return destroyed
        }

        val success = killRecursor(procHandle, includeDescendants)
        return if(success) Supported(Unit) else Unsupported
    }

    override val completionEvent = Supported<ResultEventSource> { handler ->
        process.onExit().thenAccept { handler.invoke(process.exitValue()) }
    }
}