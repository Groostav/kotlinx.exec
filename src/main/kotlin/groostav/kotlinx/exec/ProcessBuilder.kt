package groostav.kotlinx.exec

import java.nio.charset.Charset

data class ProcessBuilder internal constructor(

        /**
         * The command to execute.
         */
        var command: List<String> = emptyList(),
        /**
         * Environment parameters to be applied for the child process
         */
        var environment: Map<String, String> = System.getenv(),

        /**
         * line delimiters used for parsing lines out of standard-error and standard-out
         * for the the aggregate channel
         */
        var delimiters: List<String> = listOf("\r", "\n", "\r\n"),

        /**
         * value that results in flushing values to operating system standard-input buffers.
         */
        // this sucks, cant use delimeters because you might flush \r separately from \n.
        // which does illicit a different behaviour from powershell.
        var inputFlushMarker: Char = '\n',


        var encoding: Charset = Charsets.UTF_8,

        /**
         * Amount of raw-character output buffered by [RunningProcess.standardError]
         *
         * This value controls the number of characters buffered for the standard-output character stream.
         *
         * This buffer is only for the character stream, line-aggregation is done before buffering and is
         * buffered by the aggregate channel as part of the [aggregateOutputBufferLineCount]
         *
         * Other buffers may be acquired as part of the child APIs,
         * namely the [java.lang.Process.getInputStream]'s representation of standard-output
         * is buffered by a default [java.io.BufferedInputStream.DEFAULT_BUFFER_SIZE]
         * at time of writing. There is, to my knowledge, no strategy to change this buffer short of
         * class-loader or byte-code manipulations.
         */
        var standardErrorBufferCharCount: Int = 8192,

        /**
         * Amount of raw-character output buffered by [RunningProcess.standardOutput]
         *
         * This value controls the number of characters buffered for the standard-error character stream.
         *
         * This buffer is only for the character stream, line-aggregation is done before buffering and is
         * buffered by the aggregate channel as part of the [aggregateOutputBufferLineCount]
         */
        var standardOutputBufferCharCount: Int = 8192,

        /**
         * Number of lines to buffer in the aggregate channel
         *
         * This value controls the number of lines of combined standard-output and standard-error
         * that will be kept by the running process. In the event that this buffer is filled,
         * the oldest line will be dropped, giving a behaviour similar to posix `tail`.
         */
        var aggregateOutputBufferLineCount: Int = 200,

        /**
         * The amount of time to wait before considering a SIG_INT kill command to have failed.
         *
         * Using a time of zero will result in no SIG_INT signals at all, instead using only kill -9,
         * or similar techniques.
         */
        var gracefulTimeousMillis: Long = 1500L,

        /**
         * Indication of whether a `kill` call should be interpreted a _kill process-tree_ or _kill (single process)_
         *
         * if `true`, this may subsantially increase the time the `kill` command takes.
         */
        // TODO: what about a docker-style program, that quickly forks child-processes and the exits?
        // can we call `kill(includeDescendants=true)` on a process thats already stopped, in an attempt
        // to end its child processes?
        // Such a feature would involve a graph traversal problem similar to those of the GC.
        var includeDescendantsInKill: Boolean = false,

        /**
         * Indication of how exit values should be interpreted, wherein 'normal' or 'success' exit codes result
         * in regular `return` statements, and 'error' or 'failure' exit codes result in thrown exceptions.
         *
         * How this set is interpreted:
         *
         * 1. If a process exits with a code that is in this set, then calls to await [RunningProcess.exitCode]
         *    will yield that value.
         * 2. If the set is empty, all exit codes will be treated as valid and be yielded from
         *    [RunningProcess.exitCode]
         * 3. If a process exists with a code that is not in this list
         *    and the list is not empty, an [InvalidExitValueException]
         *    is thrown when awaiting [RunningProcess.exitCode].
         */
        var expectedOutputCodes: Set<Int> = setOf(0),

        /**
         * Number of lines to be kept for generation of the exception on a bad exit code.
         *
         * [InvalidExitValueException]s are thrown when an unexpected exit code is generatated,
         * those exceptions include a message that contains the most recent messages written to standard-error.
         * This value changes the number of lines that will be buffered for the purpose of generating that message.
         *
         * Setting this value to zero will disable standard-error buffering for the purposes
         * of stack-trace generation entirely.
         */
        var linesForExceptionError: Int = 15,

        //used to point at caller of exec() through suspension context
        internal var source: ExecEntryPoint? = null
)

internal inline fun processBuilder(configureBlock: ProcessBuilder.() -> Unit): ProcessBuilder {

    val initial = ProcessBuilder().apply(configureBlock)
    val initialCommandList = initial.command.toList()

    val result = initial.copy (
            command = initialCommandList,
            delimiters = initial.delimiters.toList(),
            expectedOutputCodes = initial.expectedOutputCodes.toSet(),
            environment = if(initial.environment === System.getenv()) initial.environment else initial.environment.toMap()

            //dont deep-copy source, since its internal
    )

    result.apply {
        require(initialCommandList.any()) { "cannot exec empty command: $this" }
        require(initialCommandList.all { '\u0000' !in it }) { "cannot exec command with null character: $this"}
        require(standardErrorBufferCharCount >= 0) { "cannot exec with output buffer size less than zero: $this"}
        require(delimiters.all { it.any()}) { "cannot parse output lines with empty delimeter: $this" }
        require(aggregateOutputBufferLineCount >= 0)
        require(standardErrorBufferCharCount >= 0)
        require(standardOutputBufferCharCount >= 0)

        require(source != null) { "internal error: no known start point for trace" }
    }

    return result
}

interface ExecEntryPoint
class AsynchronousExecutionStart(command: List<String>): RuntimeException(command.joinToString(" ")), ExecEntryPoint
class SynchronousExecutionStart(command: List<String>): RuntimeException(command.joinToString(" ")), ExecEntryPoint