package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext


data class ProcessResult(val outputAndErrorLines: List<String>, val exitCode: Int)

suspend fun exec(
    command: String,
    vararg arg: String,
    environment: Map<String, String> = InheritedDefaultEnvironment,
    workingDirectory: Path = Paths.get("").toAbsolutePath(),
    delimiters: List<String> = listOf("\r", "\n", "\r\n"),
    encoding: Charset = Charsets.UTF_8,
    standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
    standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
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
    standardErrorBufferCharCount, standardOutputBufferCharCount, aggregateOutputBufferLineCount,
    gracefulTimeoutMillis, includeDescendantsInKill, expectedOutputCodes, linesForExceptionError
)

suspend fun exec(
    commandLine: List<String>,
    environment: Map<String, String> = InheritedDefaultEnvironment,
    workingDirectory: Path = Paths.get("").toAbsolutePath(),
    delimiters: List<String> = listOf("\r", "\n", "\r\n"),
    encoding: Charset = Charsets.UTF_8,
    standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
    standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
    aggregateOutputBufferLineCount: Int = 2000,
    gracefulTimeoutMillis: Long = 1500L,
    includeDescendantsInKill: Boolean = false,
    expectedOutputCodes: Set<Int>? = setOf(0), //see also
    linesForExceptionError: Int = 15
): ProcessResult {

    val config = copyAndValidate(ProcessConfiguration(
        commandLine, environment, workingDirectory,
        delimiters, encoding,
        standardErrorBufferCharCount, standardOutputBufferCharCount, aggregateOutputBufferLineCount,
        gracefulTimeoutMillis, includeDescendantsInKill, expectedOutputCodes, linesForExceptionError
    ))

    val coroutine = ExecCoroutine(coroutineContext + Dispatchers.IO, config, lazy = false)
    coroutine.start(CoroutineStart.DEFAULT, coroutine.body)
//    coroutine.start() //this doesnt call initParent ...?
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
        standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
        standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
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
        standardErrorBufferCharCount, standardOutputBufferCharCount, aggregateOutputBufferLineCount,
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
        standardErrorBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
        standardOutputBufferCharCount: Int = 2 * 1024 * 1024, // 2MB
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
        standardErrorBufferCharCount, standardOutputBufferCharCount, aggregateOutputBufferLineCount,
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
    val coroutine = ExecCoroutine(newContext, configuration, lazy)
    val start = if(lazy) CoroutineStart.LAZY else CoroutineStart.DEFAULT
    coroutine.start(start, coroutine.body)
//    coroutine.start() //this doesn't call initParent()
    return coroutine
}

val KilledBeforeStartedExitCode = -1
val CancelledExitCode = -2
val InternalErrorExitCode = -3
val NotYetPostedExitCode = -4
val KilledWithoutObtrudingCodeExitCode = -5
val ColumnLimit = 512