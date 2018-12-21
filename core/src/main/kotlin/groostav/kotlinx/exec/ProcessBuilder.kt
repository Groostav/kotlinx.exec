package groostav.kotlinx.exec

import kotlinx.coroutines.CoroutineScope
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import java.lang.ProcessBuilder as PRocBuilder

data class ProcessBuilder internal constructor(

        /**
         * The command to execute.
         */
        var command: List<String> = emptyList(),

        /**
         * Environment parameters to be applied for the child process
         */
        var environment: Map<String, String> = InheritedDefaultEnvironment,

        /**
         * The working directory under which the child process will be run.
         *
         * Defaults to the current working directory of this process.
         */
        var workingDirectory: Path = Paths.get("").toAbsolutePath(),

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
         * character count of output buffered by [RunningProcess.standardError].
         *
         * This value controls the number of characters buffered for the standard-error character channel.
         *
         * This buffer is only for the character stream, line-aggregation is done before buffering and is
         * buffered by the aggregate channel as part of the [aggregateOutputBufferLineCount]
         *
         * Note also, implementations of java platform types may allocate their own small buffers,
         * - [java.io.InputStreamReader]'s use of a 8KB buffer in [sun.nio.cs.StreamDecoder].
         * - [java.lang.Process.getInputStream] also returns a [java.io.BufferedInputStream]
         *   instance with a non-configurable byte-buffer of 8KB [java.io.BufferedInputStream.DEFAULT_BUFFER_SIZE]
         */
        var standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB

        /**
         * character count of output buffered by [RunningProcess.standardOutput].
         *
         * This value controls the number of characters buffered for the standard-output character stream.
         *
         * Note also, implementations of java platform types may allocate their own small buffers,
         * namely [java.io.InputStreamReader]'s use of a 8KB buffer in [sun.nio.cs.StreamDecoder]
         *
         * This buffer is only for the character stream, line-aggregation is done before buffering and is
         * buffered by the aggregate channel as part of the [aggregateOutputBufferLineCount]
         */
        var standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB

        /**
         * Number of lines to buffer in the aggregate channel
         *
         * This value controls the number of lines of combined standard-output and standard-error
         * that will be kept by the running process. In the event that this buffer is filled,
         * the oldest line will be dropped, giving a behaviour similar to posix `tail`.
         */
        var aggregateOutputBufferLineCount: Int = 2000,

        /**
         * The amount of time to wait before considering a SIG_INT kill command to have failed.
         *
         * Using a time of zero will result in no SIG_INT signals at all, instead using only kill -9,
         * or similar techniques.
         */
        var gracefulTimeousMillis: Long = 1500L,

        /**
         * Indication of whether a `kill` call should be interpreted aa _kill process-tree_ or _kill (single process)_
         *
         * if `true`, this may substantially increase the time the `kill` command takes.
         */
        var includeDescendantsInKill: Boolean = false,

        /**
         * Specifies the exit codes that are considered successful, determining whether the [RunningProcess]
         * completes normally or fails with an exception.
         *
         * If a [RunningProcess] instance exits...
         * 1. ...and this set is `null` then all exit codes will be treated as valid will be used to complete [RunningProcess.exitCode]
         * 2. ...with an exit code that is in this set then it will be used to complete [RunningProcess.exitCode]
         * 3. ...with an exit code that is not in this non-null set then [RunningProcess.exitCode] throws [InvalidExitValueException].
         */
        var expectedOutputCodes: Set<Int>? = setOf(0), //see also

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
        internal var source: ExecEntryPoint? = null,
        internal val scope: CoroutineScope
)

object InheritedDefaultEnvironment: Map<String, String> by System.getenv()

private fun String.encodeLineChars() = this
        .replace("\r", "\\r")
        .replace("\n", "\\n")

internal inline fun processBuilder(coroutineScope: CoroutineScope, configureBlock: ProcessBuilder.() -> Unit): ProcessBuilder {

    val initial = ProcessBuilder(scope = coroutineScope).apply(configureBlock)
    val initialCommandList = initial.command.toList()

    val result = initial.copy (
            command = initialCommandList,
            delimiters = initial.delimiters.toList(),
            expectedOutputCodes = initial.expectedOutputCodes?.toSet(),
            environment = if(initial.environment === InheritedDefaultEnvironment) initial.environment else initial.environment.toMap()

            //dont deep-copy source, since its internal
    )

    result.run {
        require(initialCommandList.any()) { "cannot exec empty command" }
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

private inline fun ProcessBuilder.require(requirement: Boolean, message: () -> String) {
    if( ! requirement){
        throw InvalidExecConfigurationException(message(), this)
    }
}