package groostav.kotlinx.exec

internal typealias PID = Int

internal interface ProcessIDGenerator {
    /**
     * The OS-relevant process ID integer.
     */
    fun findPID(process: Process): PID

    interface Factory {
        fun create(): Maybe<ProcessIDGenerator>
    }
}

internal fun makePIDGenerator(): ProcessIDGenerator {
    val factories = listOf(
            JEP102ProcessIDGenerator,
            WindowsReflectiveNativePIDGen, UnixReflectivePIDGen,
            ZeroTurnaroundPIDGenerator
    )

    return factories.firstSupporting { it.create() }
}
