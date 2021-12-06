package groostav.kotlinx.exec

import groostav.kotlinx.exec.ProcessOS.Unix
import groostav.kotlinx.exec.ProcessOS.Windows

fun emptyScriptCommand() = when(JavaProcessOS) {
    Windows -> `powershell -ExecPolicy Bypass -File`("EmptyScript.ps1")
    Unix -> bash("EmptyScript.sh")
}
fun completableScriptCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("CompletableScript.ps1")
    Unix -> bash("CompletableScript.sh")
}
fun singleParentSingleChildCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("SingleParentSingleChildCommand.ps1")
    Unix -> TODO()
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
    Windows -> cmd("ChattyErrorScript.bat")
    Unix -> bash("ChattyErrorScript.sh")
}
fun printASDFEnvironmentParameterCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("PrintASDFEnvironmentParameter.ps1")
    Unix -> bash("PrintASDFEnvironmentParameter.sh")
}
fun readToExitValue() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("ReadToExitValue.ps1")
    Unix -> TODO()
}
fun hangingCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("Hanging.ps1")
    Unix -> TODO()
}
fun interruptableHangingCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("InterruptableHanging.ps1")
    Unix -> TODO()
}
fun fluidProcessCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("FluidChildProcesses.ps1")
    Unix -> TODO()
}
fun iliadCommand() = when(JavaProcessOS){
    Windows -> `powershell -ExecPolicy Bypass -File`("IliadRepeater.ps1")
    Unix -> TODO()
}

//TODO make this as platform agnostic as possible, at least support ubuntu/centOS
private fun `powershell -ExecPolicy Bypass -File`(scriptFileName: String) = listOf(
        "powershell.exe",
        "-ExecutionPolicy", "Bypass",
        "-File", getLocalResourcePath(scriptFileName)
)
private fun bash(scriptFileName: String) = listOf("bash", getLocalResourcePath(scriptFileName))
private fun cmd(scriptFileName: String) = listOf("cmd.exe", "/C", getLocalResourcePath(scriptFileName))



