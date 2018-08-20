import groostav.kotlinx.exec.JavaProcessOS
import groostav.kotlinx.exec.ProcessOS
import groostav.kotlinx.exec.WindowsTests
import groostav.kotlinx.exec.exec
import java.nio.file.Paths
import java.util.*
import kotlin.test.assertFalse

inline fun <T> queueOf(vararg elements: T): Queue<T> {
    val result = LinkedList<T>()
    elements.forEach { result.add(it) }
    return result
}

fun getLocalResourcePath(localName: String): String {
    val rsx = WindowsTests::class.java.getResource(localName) ?: throw UnsupportedOperationException("cant find $localName")
    val resource = Paths.get(rsx.toURI()).toString()
    return resource
}

//TODO make this as platform agnostic as possible, at least support ubuntu/centOS
private fun `powershell -ExecPolicy Bypass -File`(scriptFileName: String) = listOf(
        "powershell.exe",
        "-File", getLocalResourcePath(scriptFileName),
        "-ExecutionPolicy", "Bypass"
)
private fun bash(scriptFileName: String) = listOf("bash", getLocalResourcePath(scriptFileName))

fun emptyScriptCommand() = when(JavaProcessOS) {
    ProcessOS.Windows -> `powershell -ExecPolicy Bypass -File`("EmptyScript.ps1")
    ProcessOS.Unix -> bash("EmptyScript.sh")
}
fun completableScriptCommand() = when(JavaProcessOS){
    ProcessOS.Windows -> `powershell -ExecPolicy Bypass -File`("CompletableScript.ps1")
    ProcessOS.Unix -> bash("CompletableScript.sh")
}
fun promptScriptCommand() = when(JavaProcessOS){
    ProcessOS.Windows -> `powershell -ExecPolicy Bypass -File`("PromptScript.ps1")
    ProcessOS.Unix -> bash("PromptScript.sh")
}
fun errorAndExitCodeOneCommand() = when(JavaProcessOS){
    ProcessOS.Windows -> `powershell -ExecPolicy Bypass -File`("ExitCodeOne.ps1")
    ProcessOS.Unix -> bash("ExitCodeOne.sh")
}
fun printWorkingDirectoryCommand() = when(JavaProcessOS){
    ProcessOS.Windows -> listOf("cmd.exe", "/C", "cd")
    ProcessOS.Unix -> listOf("bash pwd")
}
fun forkerCommand() = when(JavaProcessOS){
    ProcessOS.Windows -> `powershell -ExecPolicy Bypass -File`("forker/forker-compose-up.ps1")
    else -> TODO()
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

internal suspend fun assertNotListed(deadProcessID: Int){

    // both powershell and ps have output formatting options,
    // but I'd rather demo working line-by-line with a regex.

    val runningPIDs: List<Int> = when(JavaProcessOS){
        ProcessOS.Unix -> {
            val firstIntOnLineRegex = Regex(
                    "(?<pid>\\d+)\\s+"+
                    "(?<terminalName>\\S+)\\s+"+
                    "(?<time>\\d\\d:\\d\\d:\\d\\d)\\s+"+
                    "(?<processName>\\S+)"
            )
            exec("ps", "-a")
                    .outputAndErrorLines
                    .drop(1)
                    .map { it.trim() }
                    .map { pidRecord ->
                        firstIntOnLineRegex.matchEntire(pidRecord)?.groups?.get("pid")?.value?.toInt()
                                ?: TODO("no PID on `ps` record '$pidRecord'")
                    }
        }
        ProcessOS.Windows -> {
            val getProcessLineRegex = Regex(
                    "(?<handleCount>\\d)+\\s+" +
                    "(?<nonPagedMemKb>\\d)+\\s+" +
                    "(?<pagedMemKb>\\d+)\\s+" +
                    "(?<workingSetKb>\\d+)\\s+" +
                    "((?<processorTimeSeconds>\\S+)\\s+)?" +
                    "(?<pid>\\d+)\\s+" +
                    "(?<somethingImportant>\\d+)\\s+" +
                    "(?<processName>.*)"
            )
            exec("powershell.exe", "-Command", "Get-Process")
                    .outputAndErrorLines
                    .drop(3) //powershell preamble is a blank line, a header line, and an ascii horizontal separator line
                    .map { it.trim() }
                    .dropLastWhile { it.isBlank() }
                    .map { pidRecord ->
                        getProcessLineRegex.matchEntire(pidRecord)?.groups?.get("pid")?.value?.toInt()
                                ?: TODO("no PID on `GetProcess` record '$pidRecord'")
                    }
        }

    }

    assertFalse(deadProcessID in runningPIDs)
}