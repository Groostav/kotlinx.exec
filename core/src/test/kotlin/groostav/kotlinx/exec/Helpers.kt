package groostav.kotlinx.exec


import groostav.kotlinx.exec.ProcessOS.Unix
import groostav.kotlinx.exec.ProcessOS.Windows
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.time.onTimeout
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import kotlin.test.assertTrue

inline fun <T> queueOf(vararg elements: T): Queue<T> {
    val result = LinkedList<T>()
    elements.forEach { result.add(it) }
    return result
}

private object ReflectionHardPoint

fun getLocalResourcePath(localName: String): String {
    val rsx = ReflectionHardPoint::class.java.getResource(localName) ?: throw UnsupportedOperationException("cant find $localName")
    val resource = Paths.get(rsx.toURI()).toString()
    return resource
}

inline fun <reified X: Exception> assertThrows(action: () -> Any?): X? {
    val errorPrefix = "expected action to throw ${X::class.simpleName}"
    try {
        action()
    }
    catch(ex: Exception){
        if(ex is X) return ex
        else throw IllegalStateException("$errorPrefix, but it threw ${ex::class.simpleName}", ex)
    }

    throw IllegalStateException("$errorPrefix, but it did not.")
}

internal inline fun <reified X: Exception> Catch(action: () -> Any?): X? =
        try { action(); null } catch(ex: Exception){ if(ex is X) ex else throw ex }

internal suspend fun assertNotListed(vararg deadProcessIDs: Int){

    // both powershell and ps have output formatting options,
    // but I'd rather demo working line-by-line with a regex.

    val runningPIDs: List<Int> = pollRunningPIDs().sorted()
    val zombies = deadProcessIDs.toSet() intersect runningPIDs.toSet()

    assertTrue(zombies.isEmpty(), "${zombies.joinToString()} is still running")
}

internal suspend fun waitForTerminationOf(pid: Int, timeout: Long = 30_000){

    val finished = withTimeoutOrNull(timeout){
        while(pid in pollRunningPIDs()) delay(20)
        require(pid !in pollRunningPIDs())
        Unit
    }

    require(finished != null) { "timed-out waiting for completion of $pid" }
}

internal suspend fun pollRunningPIDs(): List<Int> {
    val runningPIDs: List<Int> = when (JavaProcessOS) {
        Unix -> {
            val firstIntOnLineRegex = Pattern.compile(
                    "(?<pid>\\d+)\\s+" +
                            "(?<terminalName>\\S+)\\s+" +
                            "(?<time>\\d\\d:\\d\\d:\\d\\d)\\s+" +
                            "(?<processName>\\S+)"
            )
            exec("ps", "-a")
                    .outputAndErrorLines
                    .drop(1)
                    .map { it.trim() }
                    .map { pidRecord ->
                        firstIntOnLineRegex.matcher(pidRecord).apply { find() }.group("pid")?.toInt()
                                ?: TODO("no PID on `ps` record '$pidRecord'")
                    }
        }
        Windows -> {
            val getProcessLineRegex = Pattern.compile(
                    "(?<handleCount>\\d)+\\s+" +
                            "(?<nonPagedMemKb>\\d)+\\s+" +
                            "(?<pagedMemKb>\\d+)\\s+" +
                            "(?<workingSetKb>\\d+)\\s+" +
                            "((?<processorTimeSeconds>\\S+)\\s+)?" +
                            "(?<pid>\\d+)\\s+" +
                            "(?<somethingImportant>\\d+)\\s+" +
                            "(?<processName>.*)"
            )
            val getProcess = exec {
                command = listOf("powershell.exe", "-Command", "Get-Process")
                debugName = "Get-Process.ps1"
            }
            getProcess.outputAndErrorLines
                    .drop(3) //powershell preamble is a blank line, a header line, and an ascii horizontal separator line
                    .map { it.trim() }
                    .dropLastWhile { it.isBlank() }
                    .map { pidRecord ->
                        getProcessLineRegex.matcher(pidRecord).apply { find() }.group("pid")?.toInt()
                                ?: TODO("no PID on `GetProcess` record '$pidRecord'")
                    }
        }
    }
    return runningPIDs
}