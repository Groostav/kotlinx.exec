package groostav.kotlinx.exec

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.channels.consumeEach
import com.sun.jna.platform.win32.WinDef.BOOL
import kotlinx.coroutines.channels.map
import kotlinx.coroutines.channels.toList


//note this class may be preferable to the jep102 based class because kill gracefully (aka normally)
// isnt supported on windows' implementation of ProcessHandle.
internal class WindowsProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = if(Platform.isWindows()) {
            Supported(WindowsProcessControl(process, pid))
        }
        else OS_NOT_WINDOWS
    }

    @InternalCoroutinesApi
    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Supported<Unit> {

//        var command = listOf("taskkill")
//        if(includeDescendants) command += "/T"
//        command += listOf("/PID", "$pid")

//        fail; //aww christ:
        // http://stanislavs.org/stopping-command-line-applications-programatically-with-ctrl-c-events-from-net/
        // turns out that taskkill /PID 1234 sends WM_CLOSE, which isnt exactly a SIG_INT,
        // and that many applications, including powershell, simply ignore it.
        // it seems like what is suggested is attempting to enumerate windows and the threads running their procedures,
        // and send each a WM_QUIT or WM_CLOSE message.

        // soembody has done some lifting for java:
        // https://stackoverflow.com/a/42839731/1307108

        // note also, you can call EndTask on Win32: https://docs.microsoft.com/en-us/windows/desktop/api/winuser/nf-winuser-endtask

        // then theres this guy: http://web.archive.org/web/20170909040729/http://www.latenighthacking.com/projects/2003/sendSignal/

        // ok, so maybe we can employ both solutions?
        // use k32 to find out if the process has windows, find out which threads govern those windows, emit "WM_CLOSE" to those threads
        // then spawn a new process, attach a console and emit a "CTRL_C" message?

        val separator = System.getProperty("file.separator")
        val path = "${System.getProperty("java.home")}${separator}bin${separator}javaw"

        runBlocking {
            GlobalScope.execAsync {
                command = listOf(
                        path,
                        "-cp", System.getProperty("java.class.path"),
                        PoliteLeechKiller::class.java.name,
                        "-pid", pid.toString()
                )
                expectedOutputCodes = null
            }.map { System.err.println(it.formattedMessage) }.toList()
        }

//        PoliteLeechKiller.main(arrayOf("-pid", pid.toString()))

        return Supported(Unit)
    }

    object PoliteLeechKiller {
        @JvmStatic fun main(args: Array<String>){
            println("running! args=[${args.joinToString()}]")
            require(args.size == 2) { "expected args: -pid <pid_int>"}
            require(args[0] == "-pid") { "expected args: -pid <pid_int>, but args[0] was ${args[0]}"}
            require(args[1].toIntOrNull() != null) { "expected -pid as integer value, but was ${args[1]}"}

            //https://stackoverflow.com/questions/1229605/is-this-really-the-best-way-to-start-a-second-jvm-from-java-code
            // attaching a breakpoint: https://www.youtube.com/watch?v=fBGWtVOKTkM
            // this code is run in another vm/process!
            val pid = args[1].toInt()

            fun tryPrintExecutePrint(description: String, nativeFunc: KERNEL_32.() -> Boolean): Unit? {

                println("calling $description...")
                val success = KERNEL_32.nativeFunc()
                val errorCode = KERNEL_32.GetLastError()
                println("success=$success, code=$errorCode")
                return if(success) Unit else null
            }

            try {
                tryPrintExecutePrint("SetConsoleCtrlHandler(NULL, TRUE)"){
                    SetConsoleCtrlHandler(Pointer.NULL, BOOL(true))
                }

                val (eventName, eventCode) = "CTRL_C_EVENT" to Kernel32.CTRL_C_EVENT
                println("submitting $eventName to pid=$pid")

                tryPrintExecutePrint("Kernel32.AttachConsole($pid)"){
                    AttachConsole(pid)
                }
                tryPrintExecutePrint("Kernel32.GenerateConsoleCtrlEvent($eventName, 0)") {
                    GenerateConsoleCtrlEvent(eventCode, 0)
                }

                println("done.")
            }
            catch(ex: Throwable){
                ex.printStackTrace()
                System.exit(42)
            }

            System.exit(0)
        }
    }

    @InternalCoroutinesApi
    override fun killForcefullyAsync(includeDescendants: Boolean): Supported<Unit> {

        var command = listOf("taskkill")
        if(includeDescendants) command += "/T"
        command += "/F"
        command += listOf("/PID", "$pid")

        GlobalScope.launch(Unconfined + CoroutineName("process(PID=$pid).killForcefully")) {
            execAsync { this.command = command }.consumeEach { trace { it.formattedMessage } }
        }

        return Supported(Unit)
    }

    //also, implementing a WindowsListener.addExitCodeHandle with RegisterWaitForSingleObject function sounds good,
    // https://docs.microsoft.com/en-us/windows/desktop/api/winbase/nf-winbase-registerwaitforsingleobject
    // but it looks like that just punts the problem from the jvm into kernel 32, which still uses the same
    // (blocking thread) strategy.
    // => dont bother, no matter the API we're still polling the bastard.
}

internal class WindowsReflectiveNativePIDGen(): ProcessIDGenerator {

    init {
        if(JavaVersion >= 9) trace { "WARN: using Windows reflection-based PID generator on java-9" }
    }

    companion object: ProcessIDGenerator.Factory {

        override fun create() = if (JavaProcessOS != ProcessOS.Windows) OS_NOT_WINDOWS else {
            Supported(WindowsReflectiveNativePIDGen())
        }
    }

    private val field = Class.forName("java.lang.ProcessImpl")
            .getDeclaredField("handle")
            .apply { isAccessible = true }

    override fun findPID(process: Process): Int {

        val handlePeer = field.getLong(process)
        val handle = WinNT.HANDLE(Pointer.createConstant(handlePeer))
        val pid = KERNEL_32.GetProcessId(handle)

        return pid
    }
}

private typealias PHANDLER_ROUTINE = Pointer?

interface KERNEL_32: Kernel32 {

    fun SetConsoleCtrlHandler(handler: PHANDLER_ROUTINE, add: BOOL): Boolean

    companion object: KERNEL_32 by Native.load("kernel32", KERNEL_32::class.java, W32APIOptions.DEFAULT_OPTIONS)
}


private val OS_NOT_WINDOWS = Unsupported("os is not windows")