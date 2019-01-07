package groostav.kotlinx.exec

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.channels.consumeEach
import com.sun.jna.platform.win32.WinDef.BOOL
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.channels.map
import kotlinx.coroutines.channels.toList
import java.util.*
import kotlin.collections.HashSet


//note this class may be preferable to the jep102 based class because kill gracefully (aka normally)
// isnt supported on windows' implementation of ProcessHandle.
internal class WindowsProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = if(Platform.isWindows()) {
            Supported(WindowsProcessControl(process, pid))
        }
        else OS_NOT_WINDOWS
    }

    private val procHandle: WinNT.HANDLE = fail

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

        // regarding sub-process tree: https://github.com/Microsoft/vscode-windows-process-tree/blob/d0cd703b508dd6f9c5ca9bb8ef928fdc7293282f/src/process.cc

        val childIDsByParent = buildPIDIndex()

        println("built PPID table: $childIDsByParent")
        println("this process has children: ${childIDsByParent[pid.toLong()]}")

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

internal class Win32ProcessProxy(val handle: WinNT.HANDLE) {

    fun pollIsAlive(): Boolean {
        val exitCode = IntByReference()
        val hasExitCode = KERNEL_32.GetExitCodeProcess(handle, exitCode)
        return ! hasExitCode
    }

    fun buildChildrenSnapshot(): List<Win32ProcessProxy> {

        var changed: Boolean
        val children: HashSet<WinNT.HANDLE> = HashSet()
        val ppid = KERNEL_32.GetProcessId(handle).toLong()

        // ok so, this is a fixed point, we simply use the racy API,
        // then acquire locks on the objects implied by the racy API,
        // and if the objects have changed since we last checked,
        // we dispose those objects and try again.
        do {
            val index = buildPIDIndex()
            val childrenIds = index[ppid] ?: emptyList<Long>()
            val newChildren = childrenIds.map { childPID ->
                KERNEL_32.OpenProcess(WinNT.SYNCHRONIZE, false, childPID.toInt())
            }

            val incorrect = children - newChildren

            val foundStaleChildren = children.retainAll(newChildren)
            val foundNewChildren = children.addAll(newChildren)

            changed = foundNewChildren || foundStaleChildren

            incorrect.forEach { KERNEL_32.CloseHandle(it) }
        }
        while(changed)

        return children.map { Win32ProcessProxy(it) }
    }
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

private object PoliteLeechKiller {
    @JvmStatic fun main(args: Array<String>){
        println("running! args=[${args.joinToString()}]")
        require(args.size == 2) { "expected args: -pid <pid_int>"}
        require(args[0] == "-pid") { "expected args: -pid <pid_int>, but args[0] was ${args[0]}"}
        require(args[1].toIntOrNull() != null) { "expected -pid as integer value, but was ${args[1]}"}

        //https://stackoverflow.com/questions/1229605/is-this-really-the-best-way-to-start-a-second-jvm-from-java-code
        // attaching a breakpoint: https://www.youtube.com/watch?v=fBGWtVOKTkM
        // this code is run in another vm/process!
        val pid = args[1].toInt()

        fun printExecutePrintAndExitIfFailed(description: String, nativeFunc: KERNEL_32.() -> Boolean): Unit? {

            println("calling $description...")
            val success = KERNEL_32.nativeFunc()
            val errorCode = KERNEL_32.GetLastError()
            println("success=$success, code=$errorCode")
            return if(success) Unit else System.exit(errorCode)
        }

        try {
            printExecutePrintAndExitIfFailed("SetConsoleCtrlHandler(NULL, TRUE)"){
                SetConsoleCtrlHandler(Pointer.NULL, BOOL(true))
            }

            val (eventName, eventCode) = "CTRL_C_EVENT" to Kernel32.CTRL_C_EVENT
            println("submitting $eventName to pid=$pid")

            printExecutePrintAndExitIfFailed("Kernel32.AttachConsole($pid)"){
                AttachConsole(pid)
            }
            printExecutePrintAndExitIfFailed("Kernel32.GenerateConsoleCtrlEvent($eventName, 0)") {
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

private fun buildPIDIndex(): TreeMap<Long, ArrayList<Long>> {
    val processEntry: Tlhelp32.PROCESSENTRY32 = Tlhelp32.PROCESSENTRY32.ByReference() //dwsize set by jna.platform!
    val snapshotHandle: WinNT.HANDLE = KERNEL_32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))

    val childIDsByParent = TreeMap<Long, ArrayList<Long>>()
    if (KERNEL_32.Process32First(snapshotHandle, processEntry)) {
        do {
            val pid = processEntry.th32ProcessID.toLong()
            val ppid = processEntry.th32ParentProcessID.toLong()

            //to get a handle I think we need OpenProcess

            if (pid != 0L) {
                if (ppid in childIDsByParent) {
                    childIDsByParent.getValue(ppid).add(pid)
                }
                else {
                    childIDsByParent[ppid] = arrayListOf(pid)
                }
            }
        } while (KERNEL_32.Process32Next(snapshotHandle, processEntry))
    }

    // ok so by pulling the process HANDLE you can effectively lock the PID.
    // https://stackoverflow.com/questions/26301382/does-windows-7-recycle-process-id-pid-numbers
    // https://docs.microsoft.com/en-ca/windows/desktop/ProcThread/process-handles-and-identifiers
    // then it seems to me I'm writing a garbage collector...
    // i dont know how to write a garbage collector...

    //hows this:
    // val children
    // let queue: Queue<Pair<Pid, HANDLE>> = snapshotChildren(pid)
    // while(current = queue.remove() != null && isAlive) {
    //   queue.addAll(snapshotChildren(current.pid)
    //   current.interrupt()
    //   current.handle.join() -- this should block until that objects interruption logic has finished, presumably itself joining on children.
    // }
    //
    // some problems: you might get a half-baked tree.
    // what if some children respond to interrupt but others dont?
    // what if the root does but the children dont? then taskkill /T wont work, you've effectively got yourself stuck.
    //    well, MS might outsmart me here: https://blogs.msdn.microsoft.com/oldnewthing/20110107-00/?p=11803/
    //    taskkill might well simply acquire handles to zombie processes and traverse them like any other.
    // but the racyness seems definate:
    //    https://blogs.msdn.microsoft.com/oldnewthing/20150403-00/?p=44313


    // but, TDDing this will be a good chunk of work.
    // perhalps rather than use powershell I could the jvm forking strategy here for testing:
    // create some jvm instnaces that each print out when they're interrupted.

    return childIDsByParent
}