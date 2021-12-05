package groostav.kotlinx.exec

import kotlin.streams.asSequence

internal object JEP102ProcessFacade {

    fun tryKillGracefullyAsync(
        tracing: CoroutineTracer,
        process: Process,
        includeDescendants: Boolean,
        deadline: Long
    ): Boolean {

        val tracing = tracing.appendName("JEP120-killGracefully")
        val procHandle = process.toHandle()

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean {

            if( ! handle.supportsNormalTermination()) return false

            //recurse on children
            val childrenDead = if(includeChildren){
                handle.children().asSequence().fold(true){ accum, next ->
                    accum && killRecursor(next, includeChildren)
                }
            }
            else true

            if( ! childrenDead) return false

            if(System.currentTimeMillis() > deadline) return false

            val destroyed = handle.destroy()
            tracing.trace { "destroy [gracefully] PID=${handle.pid()}: $destroyed}" }

            return destroyed
        }

        return killRecursor(procHandle, includeDescendants)
    }

    fun killForcefullyAsync(
        tracing: CoroutineTracer,
        process: Process,
        includeDescendants: Boolean
    ): Boolean {

        val tracing = tracing.appendName("JEP120-killForcefully")

        val procHandle = process.toHandle()

        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean {

            //recurse on children
            val childrenDead = if(includeChildren){
                handle.children().asSequence().fold(true){ accum, next ->
                    accum && killRecursor(next, includeChildren)
                }
            }
            else true

            if(! childrenDead) return false

//            if(System.currentTimeMillis() > deadline) return false

            val destroyed = handle.destroyForcibly()
            tracing.trace { "destroyed PID=${handle.pid()}: $destroyed" }

            return destroyed
        }

        return killRecursor(procHandle, includeDescendants)
    }
}