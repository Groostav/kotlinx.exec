package groostav.kotlinx.exec

import com.sun.jna.Platform
import java.lang.StringBuilder

enum class ProcessOS {
    Windows, Unix
}

internal val JavaProcessOS: ProcessOS = when {
    Platform.isWindows() || Platform.isWindowsCE() -> ProcessOS.Windows
    Platform.isLinux() || Platform.isFreeBSD() || Platform.isOpenBSD() || Platform.isSolaris() -> ProcessOS.Unix
    else -> throw UnsupportedOperationException("unsupported OS:"+System.getProperty("os.name"))
}

val DOLLAR = "$"
val TAB = "\t"

fun Throwable.stackTraceWithoutLineNumbersToString(lineCount: Int): String {

    val builder = StringBuilder()
    val lineNumPattern = Regex(":\\d+\\)")
    val ps1PathPattern = Regex("((\\w:)|(/))\\\\.*?\\.((ps1)|(sh)|(bat))")

    for(line in stackTraceToString().lines().take(lineCount)){
        builder.appendLine(
            line.replace(lineNumPattern, ":<LINE_NUM>)")
                .replace(ps1PathPattern, "<SCRIPT_PATH>")
        )
    }

    return builder.toString()
}

