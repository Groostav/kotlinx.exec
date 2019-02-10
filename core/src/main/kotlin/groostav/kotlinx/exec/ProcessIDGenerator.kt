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
    val factories = sequenceOf(
            JEP102ProcessIDGenerator,
            WindowsReflectiveNativePIDGen,
            UnixReflectivePIDGen,
            ZeroTurnaroundPIDGenerator
    )

    return factories.map { Supported(it).supporting(ProcessIDGenerator.Factory::create) }.firstSupported()
}
