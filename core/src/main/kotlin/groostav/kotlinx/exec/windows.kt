package groostav.kotlinx.exec

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.channels.consumeEach
import com.sun.jna.platform.win32.WinDef.BOOL
import com.sun.jna.platform.win32.WinDef.HWND



//note this class may be preferable to the jep102 based class because kill gracefully (aka normally)
// isnt supported on windows' implementation of ProcessHandle.
internal class WindowsProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = supportedIf(Platform.isWindows()) {
            WindowsProcessControl(process, pid)
        }
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

//        val k32 = Kernel32.INSTANCE
//
//        k32.AttachConsole(pid)
//        k32.GenerateConsoleCtrlEvent(Kernel32.CTRL_BREAK_EVENT, 0)


//        try {
//            fail; //blegh java-9;s no good.
//            val field = Class.forName("java.lang.ProcessImpl")
//                    .getDeclaredField("handle")
//                    .apply { isAccessible = true }
//
//            val handlePeer = field.getLong(process)
//            val handle = HWND(Pointer.createConstant(handlePeer))
//
//            val result = TaskEnder.INSTANCE.EndTask(handle, BOOL(true), BOOL(true))
//            trace { "endtask returned $result" }
//        }
//        catch(ex: Exception){
//            val x = 4;
//            throw ex;
//        }


//        GlobalScope.launch(Unconfined + CoroutineName("process(PID=$pid).killGracefully")) {
//            execAsync { this.command = command }.consumeEach { trace { it.formattedMessage } }
//        }

        return Supported(Unit)
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
        override fun create() = supportedIf(JavaProcessOS == ProcessOS.Windows){
            WindowsReflectiveNativePIDGen()
        }
    }

    private val field = Class.forName("java.lang.ProcessImpl")
            .getDeclaredField("handle")
            .apply { isAccessible = true }

    override fun findPID(process: Process): Int {

        val handlePeer = field.getLong(process)
        val handle = WinNT.HANDLE(Pointer.createConstant(handlePeer))
        val pid = Kernel32.INSTANCE.GetProcessId(handle)

        return pid
    }
}

interface TaskEnder: Library {
    companion object {
        val INSTANCE = Native.load("user32", TaskEnder::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun EndTask(
            hWnd: HWND,
            fShutDown: BOOL,
            fForce: BOOL
    ): BOOL

    fail; //delete this code. 

}