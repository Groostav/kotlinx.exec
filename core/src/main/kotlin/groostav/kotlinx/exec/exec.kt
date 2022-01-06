package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

internal val TRACE = System.getProperty("groostav.kotlinx.exec.trace").toBoolean()
internal val DEBUGGING_GRACEFUL_KILL: Boolean = System.getProperty("groostav.kotlinx.exec.DebuggingGracefulKill").toBoolean()

data class ProcessResult(val outputAndErrorLines: List<String>, val exitCode: Int?)

suspend fun exec(
    command: String,
    vararg arg: String,
    environment: Map<String, String> = InheritedDefaultEnvironment,
    workingDirectory: Path = Paths.get("").toAbsolutePath(),
    delimiters: List<String> = listOf("\r", "\n", "\r\n"),
    encoding: Charset = Charsets.UTF_8,
//    standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
//    standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
    aggregateOutputBufferLineCount: Int = 2000,
    gracefulTimeoutMillis: Long = 1500L,
    includeDescendantsInKill: Boolean = false,
    expectedOutputCodes: Set<Int>? = setOf(0), //see also
    linesForExceptionError: Int = 15
): ProcessResult = exec(
    ArrayList<String>(arg.size + 1).apply {
        add(command)
        addAll(arg)
    },
    environment, workingDirectory,
    delimiters, encoding,
    aggregateOutputBufferLineCount, //standardErrorBufferCharCount, standardOutputBufferCharCount,
    gracefulTimeoutMillis, includeDescendantsInKill, expectedOutputCodes, linesForExceptionError
)

suspend fun exec(
    commandLine: List<String>,
    environment: Map<String, String> = InheritedDefaultEnvironment,
    workingDirectory: Path = Paths.get("").toAbsolutePath(),
    delimiters: List<String> = listOf("\r", "\n", "\r\n"),
    encoding: Charset = Charsets.UTF_8,
//    standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
//    standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
    aggregateOutputBufferLineCount: Int = 2000,
    gracefulTimeoutMillis: Long = 1500L,
    includeDescendantsInKill: Boolean = false,
    expectedOutputCodes: Set<Int>? = setOf(0), //see also
    linesForExceptionError: Int = 15
): ProcessResult {

    val config = copyAndValidate(ProcessConfiguration(
        commandLine, environment, workingDirectory,
        delimiters, encoding,
        aggregateOutputBufferLineCount,/// standardErrorBufferCharCount, standardOutputBufferCharCount,
        gracefulTimeoutMillis, includeDescendantsInKill, expectedOutputCodes, linesForExceptionError
    ))

    val coroutine = ExecCoroutine(coroutineContext + Dispatchers.IO, config)
    if( ! coroutine.isCancelled) {
        val started = coroutine.start()
        check(started) { "unstarted coroutine failed to start" }
    }
    val exitCode = coroutine.await()
    val lines = coroutine.toList().map { it.formattedMessage }

    return ProcessResult(lines, exitCode)
}

fun CoroutineScope.execAsync(
        commandLine: List<String>,
        environment: Map<String, String> = InheritedDefaultEnvironment,
        workingDirectory: Path = Paths.get("").toAbsolutePath(),
        delimiters: List<String> = listOf("\r", "\n", "\r\n"),
        encoding: Charset = Charsets.UTF_8,
//        standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
//        standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
        aggregateOutputBufferLineCount: Int = 2000,
        gracefulTimeoutMillis: Long = 1500L,
        includeDescendantsInKill: Boolean = false,
        expectedOutputCodes: Set<Int>? = setOf(0), //see also
        linesForExceptionError: Int = 15,
        context: CoroutineContext = EmptyCoroutineContext,
        lazy: Boolean = false
): RunningProcess {
    val config = copyAndValidate(ProcessConfiguration(
        commandLine, environment, workingDirectory,
        delimiters, encoding,
        aggregateOutputBufferLineCount, //standardErrorBufferCharCount, standardOutputBufferCharCount,
        gracefulTimeoutMillis, includeDescendantsInKill, expectedOutputCodes, linesForExceptionError
    ))

    return execAsync(config, context, lazy)
}

fun CoroutineScope.execAsync(
        command: String,
        vararg arg: String,
        environment: Map<String, String> = InheritedDefaultEnvironment,
        workingDirectory: Path = Paths.get("").toAbsolutePath(),
        delimiters: List<String> = listOf("\r", "\n", "\r\n"),
        encoding: Charset = Charsets.UTF_8,
//        standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
//        standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
        aggregateOutputBufferLineCount: Int = 2000,
        gracefulTimeoutMillis: Long = 1500L,
        includeDescendantsInKill: Boolean = false,
        expectedOutputCodes: Set<Int>? = setOf(0), //see also
        linesForExceptionError: Int = 15,
        context: CoroutineContext = EmptyCoroutineContext,
        lazy: Boolean = false
): RunningProcess {

    val config = copyAndValidate(ProcessConfiguration(
        ArrayList<String>(1 + arg.size).apply {
            add(command)
            addAll(arg)
        },
        environment, workingDirectory,
        delimiters, encoding,
        aggregateOutputBufferLineCount, //standardErrorBufferCharCount, standardOutputBufferCharCount,
        gracefulTimeoutMillis, includeDescendantsInKill, expectedOutputCodes, linesForExceptionError
    ))

    return execAsync(config, context, lazy)
}


fun CoroutineScope.execAsync(
    configuration: ProcessConfiguration,
    context: CoroutineContext = EmptyCoroutineContext,
    lazy: Boolean = false
): RunningProcess {
    val newContext = newCoroutineContext(context + Dispatchers.IO)
    val coroutine = ExecCoroutine(newContext, configuration)
    if( ! coroutine.isCancelled) {
        val started = coroutine.start()
        check(started) { "unstarted coroutine failed to start" }
    }
    return coroutine
}