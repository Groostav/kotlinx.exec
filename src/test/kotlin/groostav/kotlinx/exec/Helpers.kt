import groostav.kotlinx.exec.WindowsTests
import java.nio.file.Paths
import java.util.*

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

fun emptyScriptCommand() = listOf(
        "powershell.exe",
        "-File", getLocalResourcePath("EmptyScript.ps1"),
        "-ExecutionPolicy", "Bypass"
)
fun completableScriptCommand() = listOf(
        "powershell.exe",
        "-File", getLocalResourcePath("CompletableScript.ps1"),
        "-ExecutionPolicy", "Bypass"
)
fun promptScriptCommand() = listOf(
        "powershell.exe",
        "-File", getLocalResourcePath("PromptScript.ps1"),
        "-ExecutionPolicy", "Bypass"
)
fun exitCodeOneCommand() = listOf(
        "powershell.exe",
        "-File", getLocalResourcePath("ExitCodeOne.ps1"),
        "-ExecutionPolicy", "Bypass"
)



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