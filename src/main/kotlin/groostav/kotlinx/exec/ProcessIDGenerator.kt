package groostav.kotlinx.exec

import com.sun.jna.Platform
import sun.misc.Unsafe

internal interface ProcessIDGenerator {
    /**
     * The OS-relevant process ID integer.
     */
    //TODO whats the expected behaviour if the process exited?
    val pid: Maybe<Int> get() = Unsupported
}


internal class ReflectivePIDGen(val process: Process): ProcessIDGenerator {

    override val pid: Supported<Int> by lazy {

        val processType = process::class.java

        when(processType.name) {
            "java.lang.ProcessImpl" -> {
                val field = processType.getDeclaredField("handle").apply {
                    isAccessible = true
                }
                val handle = field.get(process) as Long

                //
            }
        }

        val result = TODO()

        Supported(result)
    }
}