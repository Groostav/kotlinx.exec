package groostav.kotlinx.exec

import kotlin.streams.asSequence

internal class JEP102ProcessFacade(val process: Process) : ProcessControlFacade {

//    val procHandle = process.toHandle()

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = supportedIf(JavaVersion >= 9) { JEP102ProcessFacade(process) }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

//        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean{
//
//            if( ! handle.supportsNormalTermination()) return false
//
//            //recurse on children
//            val childSequence = handle.children().asSequence()
//            val successfulInfanticide = includeChildren && childSequence.fold(true){ accum, next ->
//                accum && killRecursor(next, includeChildren)
//            }
//
//            if ( ! successfulInfanticide) return false
//
//            val destroyed = handle.destroy()
//
//            return destroyed
//        }
//
//        val success = killRecursor(procHandle, includeDescendants)
//        return if(success) Supported(Unit) else Unsupported
        return Unsupported
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
//
//        fun killRecursor(handle: ProcessHandle, includeChildren: Boolean): Boolean{
//
//            //recurse on children
//            val childSequence = handle.children().asSequence()
//            val successfulInfanticide = includeChildren && childSequence.fold(true){ accum, next ->
//                accum && killRecursor(next, includeChildren)
//            }
//
//            if ( ! successfulInfanticide) return false
//
//            val destroyed = handle.destroyForcibly()
//
//            return destroyed
//        }
//
//        val success = killRecursor(procHandle, includeDescendants)
//        return if(success) Supported(Unit) else Unsupported
        return Unsupported
    }
}

internal class JEP102ProcessIDGenerator(private val process: Process): ProcessIDGenerator {

    companion object: ProcessIDGenerator.Factory {
        override fun create(process: Process) = supportedIf(JavaVersion >= 9) { JEP102ProcessIDGenerator(process) }
    }

    override val pid: Maybe<Int> = Unsupported //Supported(process.pid().toInt())
    //TODO: RE: `toInt`, why did they use long? do they have implementations with pid=2^31 + 1?

}