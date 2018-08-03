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

//TODO make platform agnostic, add EmptyScript.sh
val EmptyScript = getLocalResourcePath("EmptyScript.ps1")
val CompletableScript = getLocalResourcePath("CompletableScript.ps1")
val PromptScript = getLocalResourcePath("PromptScript.ps1")

fun emptyScriptCommand() = listOf(
        "powershell.exe",
        "-File", EmptyScript,
        "-ExecutionPolicy", "Bypass"
)
fun completableScriptCommand() = listOf(
        "powershell.exe",
        "-File", CompletableScript,
        "-ExecutionPolicy", "Bypass"
)
fun promptScriptCommand() = listOf(
        "powershell.exe",
        "-File", PromptScript,
        "-ExecutionPolicy", "Bypass"
)



inline fun <reified T: Exception> assertThrows(action: () -> Any?): T? {
    val errorPrefix = "expected action to throw ${T::class.simpleName}"
    try {
        action()
    }
    catch(ex: Exception){
        if(ex is T) return ex
        else throw IllegalStateException("$errorPrefix, but it threw ${ex::class.simpleName}", ex)
    }

    throw IllegalStateException("$errorPrefix, but it did not.")
}