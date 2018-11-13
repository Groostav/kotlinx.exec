package groostav.kotlinx.exec


import groostav.kotlinx.exec.ProcessOS.Unix
import groostav.kotlinx.exec.ProcessOS.Windows
import groostav.kotlinx.exec.WindowsTests
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
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
    Windows -> `powershell -ExecPolicy Bypass -File`("EmptyScript.ps1")
    Unix -> bash("EmptyScript.sh")
}
fun completableScriptCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("CompletableScript.ps1")
    Unix -> bash("CompletableScript.sh")
}
fun promptScriptCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("PromptScript.ps1")
    Unix -> bash("PromptScript.sh")
}
fun errorAndExitCodeOneCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("ExitCodeOne.ps1")
    Unix -> bash("ExitCodeOne.sh")
}
fun printWorkingDirectoryCommand() = when(JavaProcessOS){
    Windows -> listOf("cmd.exe", "/C", "cd")
    Unix -> listOf("bash", "-c", "pwd")
}
fun forkerCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("forker/forker-compose-up.ps1")
    Unix -> listOf("bash", "forker/forker-compose-up.sh")
}
fun printMultipleLinesCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("MultilineScript.ps1")
    Unix -> bash( "MultilineScript.sh")
}
fun chattyErrorScriptCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("ChattyErrorScript.ps1")
    Unix -> bash("ChattyErrorScript.sh")
}
fun printASDFEnvironmentParameterCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("PrintASDFEnvironmentParameter.ps1")
    Unix -> bash("PrintASDFEnvironmentParameter.sh")
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
        Unix -> {
            val firstIntOnLineRegex = Pattern.compile(
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
            exec("powershell.exe", "-Command", "Get-Process")
                    .outputAndErrorLines
                    .drop(3) //powershell preamble is a blank line, a header line, and an ascii horizontal separator line
                    .map { it.trim() }
                    .dropLastWhile { it.isBlank() }
                    .map { pidRecord ->
                        getProcessLineRegex.matcher(pidRecord).apply { find() }.group("pid")?.toInt()
                                ?: TODO("no PID on `GetProcess` record '$pidRecord'")
                    }
        }

    }

    assertFalse("$deadProcessID is a running pid as listed in $runningPIDs") { deadProcessID in runningPIDs }
}