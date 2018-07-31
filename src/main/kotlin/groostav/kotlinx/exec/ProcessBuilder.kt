package groostav.kotlinx.exec

import java.nio.charset.Charset


//TODO: regarding ZeroTurnarounds own "run this and get me a list of std-out"  style java builder,
// should we add a third method here to cover that same use case? Simply suspend until all output is availabe,
// and return it as a list of lines?

data class ProcessBuilder internal constructor(
        var command: List<String> = emptyList(),
        var environment: Map<String, String> = System.getenv(),

        var delimiters: List<String> = listOf("\r", "\n", "\r\n"),
        var encoding: Charset = Charsets.UTF_8,
        var charBufferSize: Int = 8192,

        var gracefulTimeousMillis: Long = 1500L,
        var includeDescendantsInKill: Boolean = false,

        var expectedOutputCodes: Set<Int> = setOf(0),
        var linesForExceptionError: Int = 15
)

internal fun processBuilder(configureBlock: ProcessBuilder.() -> Unit): ProcessBuilder {
    val result = ProcessBuilder().apply(configureBlock).let { it.copy(
            command = it.command.toList(),
            delimiters = it.delimiters.toList(),
            expectedOutputCodes = it.expectedOutputCodes.toSet(),
            environment = it.environment.toMap()
    )}

    result.apply {
        require(command.any()) { "cannot exec empty command: $this" }
        require(command.all { '\u0000' !in it }) { "cannot exec command with null character: $this"}
        require(charBufferSize >= 0) { "cannot exec with output buffer size less than zero: $this"}
    }

    return result
}

internal fun ProcessEvent.toDefaultString() = when(this){
    is StandardError -> "ERROR: $line"
    is StandardOutput -> line
    is ExitCode -> "Process finished with exit code $value"
}
