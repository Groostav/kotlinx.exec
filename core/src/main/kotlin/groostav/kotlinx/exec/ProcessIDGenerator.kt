package groostav.kotlinx.exec

internal interface ProcessIDGenerator {
    /**
     * The OS-relevant process ID integer.
     */
    fun findPID(process: Process): Int

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
