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
import com.sun.jna.platform.win32.WinError.ERROR_INVALID_HANDLE
import com.sun.jna.ptr.IntByReference
import java.io.Closeable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.jvm.internal.FunctionReference
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.jvmName

//note this class may be preferable to the jep102 based class because kill gracefully (aka normally)
// isnt supported on windows' implementation of ProcessHandle.
internal class WindowsProcessControl(val config: ProcessBuilder, val process: Process, val selfPID: Int): ProcessControlFacade {

    companion object: ProcessControlFacade.Factory {
        override fun create(config: ProcessBuilder, process: Process, pid: Int) = if(Platform.isWindows()) {
            Supported(WindowsProcessControl(config, process, pid))
        }
        else OS_NOT_WINDOWS
    }

    private val procHandle: WinNT.HANDLE = KERNEL_32.OpenProcess(WinNT.READ_CONTROL, false, selfPID)

    /**
     * Attempts to send a CTRL_C signal to the parent console of this process, interrupting it.
     *
     * This library imploys a fairly going to employ a novel solution to graceful process termination,
     * avoiding `taskkill /PID` in favor of attempting to send a CTRL_C to the parent console window.
     *
     * Using `taskkill` (without /F) assumes a windows UI application
     * in that it sends it a WM_CLOSE signal to the target process.
     * This approach does not solicit any behaviour from cmd or powershell processes
     * --which are, I believe, the lions share of use cases for this library.
     * `WM_CLOSE` signals typically cause GUI applications to launch a
     * "do you want to save or close?" dialog,
     * which, fundamentally, means the process wont close programmatically
     * (they will wait for user input to such a dialog!)
     * **thus, we're going to skip the WM_CLOSE strategy used by tasskill in its entirety.**
     *
     * Instead the solution here is to create a new process that attaches itself to the console
     * used by the target process, and emit a "CTRL_C" signal to that process.
     * This has the desired effect of stopping cmd and powershell scripts,
     * in addition to causing any appropriate powershell `Finally {}` blocks to fire.
     *
     * It has no impact on GUI applications.
     */
    @InternalCoroutinesApi
    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {

        fun exitedWhileBeingKilled() = Supported(Unit).also {
            trace { "pid $selfPID exited while being killed" }
        }
        fun timedOut() = Supported(Unit).also {
            trace { "graceful termination of $selfPID timed-out" }
        }

        val deadline = Instant.now().plusMillis(config.gracefulTimeoutMillis)

        // by the time `buildPIDIndex()` returns, it might be stale
        var previousTree: Win32ProcessProxy = when {
            includeDescendants -> {
                val index: Map<PID, List<PID>> = buildPIDIndex()
                toProcessTree(index, selfPID) ?: return exitedWhileBeingKilled()
            }
            else -> Win32ProcessProxy(selfPID, procHandle, emptyList())
        }

        // once we have the tree, this locks the PIDs to HANDLE's
        // meaning that if we generate the tree a second time, and the entry is still there,
        // its has to be one of our children.


        //todo: is handle allocation to be avoided (maybe because its expensive)?
        // If yes: write a merge here so we dont excessively create/dispose handles
        // if no: this strategy is probably fine.

        var actualTree: Win32ProcessProxy

        while(true) {
            var newTree: Win32ProcessProxy? = null
            try {
                if(Instant.now() > deadline) return timedOut()

                val currentIndex = buildPIDIndex()

                val newTree = if(includeDescendants) {
                    toProcessTree(currentIndex, selfPID) ?: return exitedWhileBeingKilled()
                }
                else {
                    Win32ProcessProxy(selfPID, procHandle, emptyList())
                }

                // if the two trees are structurally equal => we've acquired a correct snap-shot of our process tree.
                // new successor processes may have spawned. That is not covered here.
                if(newTree == previousTree){
                    actualTree = newTree
                    break;
                }

            }
            finally {
                previousTree.close()
                if(newTree != null) previousTree = newTree
            }
        }

        runBlocking {

            actualTree.use {
                val path = "${System.getProperty("java.home")}/bin/javaw"

                for (win32Proc in actualTree.toSequence()) {
                    //                        fail; //whats to stop this from recursing? what if we cancel the interruptor? what if we cancel that?
                    // also: can you re-use the same process to kill all of them?
                    // also: what if it spawns a new process while its being interrupted??
                    // --that process should probably not be cancelled right?

                    trace { "killing selfPID." }
                    val killpb = java.lang.ProcessBuilder().apply {
                        command(
                                path,
                                "-cp", System.getProperty("java.class.path"),
                                PoliteLeechKiller::main.instanceTypeName,
                                "-pid", win32Proc.pid.toString()
                        )
                        redirectError(java.lang.ProcessBuilder.Redirect.INHERIT)
                        redirectOutput(java.lang.ProcessBuilder.Redirect.INHERIT)
                    }

                    killpb.start()
                }

//                    withTimeoutOrNull(Instant.now().until(deadline, ChronoUnit.MILLIS)) {
//                        jobs.forEach { it.join() }
//                    }
            }
        }

        return Supported(Unit)
    }

    @InternalCoroutinesApi
    override fun killForcefullyAsync(includeDescendants: Boolean): Supported<Unit> {

        var command = listOf("taskkill")
        if(includeDescendants) command += "/T"
        command += "/F"
        command += listOf("/PID", "$selfPID")

        GlobalScope.launch(Unconfined + CoroutineName("process(PID=$selfPID).killForcefully")) {
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

private val KFunction<*>.instanceTypeName: String get() {
    return ((this as FunctionReference).boundReceiver::class as KClass<*>).jvmName
}

internal class Win32ProcessProxy(
        val pid: PID,
        handle: WinNT.HANDLE,
        val children: List<Win32ProcessProxy>
): Closeable {

    private var handle: WinNT.HANDLE? = handle

    fun pollIsAlive(): Boolean {
        val exitCode = IntByReference()
        val hasExitCode = KERNEL_32.GetExitCodeProcess(handle!!, exitCode)
        return ! hasExitCode
    }

    override fun close() {
        KERNEL_32.CloseHandle(handle)
        handle = null
        children.forEach { it.close() }
    }

    fun toSequence(): Sequence<Win32ProcessProxy> = children.asSequence().flatMap { it.toSequence() } + this

    override fun equals(other: Any?): Boolean =
            other is Win32ProcessProxy && other.pid == this.pid && other.children == this.children

    override fun hashCode(): Int =
            pid xor children.hashCode()

    override fun toString(): String = "Win32ProcessProxy(pid=$pid, children=$children)"


}

private fun toProcessTree(pidAdjacencyList: Map<PID, List<PID>>, rootPid: PID): Win32ProcessProxy? {

    // note: we must build the tree from the root down
    // else we may leak child references

    val handle = KERNEL_32.OpenProcess(WinNT.SYNCHRONIZE, false, rootPid)
    if(handle == KERNEL_32.NULL_HANDLE) {
        // implies child exited after we got its PID but before we got its handle
        return null
    }

    val children = pidAdjacencyList[rootPid]
            ?.map { toProcessTree(pidAdjacencyList, it) }
            ?.filterNotNull()
            ?: emptyList()

    return Win32ProcessProxy(rootPid, handle, children)
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

    companion object: KERNEL_32 by Native.load("kernel32", KERNEL_32::class.java, W32APIOptions.DEFAULT_OPTIONS){

        val NULL_HANDLE = WinNT.HANDLE(null)
    }
}


private val OS_NOT_WINDOWS = Unsupported("os is not windows")

private const val SUCCESS = 0
private const val UNKNOWN_ERROR = 1
private const val NO_CONSOLE_TO_ATTACH_TO = 2

/**
 * A Strange program that attaches itself to a PID's console input and sends it a CTRL + C signal.
 *
 * I've tested this strategy with JVM, powershell and cmd processes,
 * verifying that it both kills them and that finally blocks go off.
 *
 * This program emulates a user sending a CTRL-C signal to the console attached to any sub-process.
 */
internal object PoliteLeechKiller {
    @JvmStatic fun main(args: Array<String>){
        println("running! args=[${args.joinToString()}]")
        require(args.size == 2) { "expected args: -pid <pid_int>"}
        require(args[0] == "-pid") { "expected args: -pid <pid_int>, but args[0] was ${args[0]}"}
        require(args[1].toIntOrNull() != null) { "expected -pid as integer value, but was ${args[1]}"}

        //https://stackoverflow.com/questions/1229605/is-this-really-the-best-way-to-start-a-second-jvm-from-java-code
        // attaching a breakpoint: https://www.youtube.com/watch?v=fBGWtVOKTkM
        // this code is run in another vm/process!
        val pid = args[1].toInt()

        val result = run(pid)
        System.exit(result)
    }

    private fun run(pid: Int): Int {

        try {
            printExecutePrintAndThrowIfFailed("SetConsoleCtrlHandler(NULL, TRUE)") {
                SetConsoleCtrlHandler(Pointer.NULL, BOOL(true))
            }

            val attachResult = printExecutePrintAndThrowIfFailed(
                    "Kernel32.AttachConsole($pid)",
                    otherAllowedCodes = arrayOf(ERROR_INVALID_HANDLE)
            ) {
                AttachConsole(pid)
            }
            if (attachResult == ERROR_INVALID_HANDLE) {
                //as per https://docs.microsoft.com/en-us/windows/console/attachconsole#remarks
                println("starting taskkill /PID $pid")
                java.lang.ProcessBuilder()
                        .command("taskkill", "/PID", "$pid")
                        .redirectError(java.lang.ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(java.lang.ProcessBuilder.Redirect.INHERIT)
                        .start()
            }
            else {
                val (eventName, eventCode) = "CTRL_C_EVENT" to Kernel32.CTRL_C_EVENT
                println("submitting $eventName to pid=$pid")

                printExecutePrintAndThrowIfFailed("Kernel32.GenerateConsoleCtrlEvent($eventName, 0)") {
                    GenerateConsoleCtrlEvent(eventCode, 0)
                }
            }

            println("done.")
        }
        catch (ex: Throwable) {
            ex.printStackTrace()
            return UNKNOWN_ERROR
        }

        return SUCCESS
    }

    private fun printExecutePrintAndThrowIfFailed(
            description: String,
            otherAllowedCodes: Array<out Int> = emptyArray(),
            nativeFunc: KERNEL_32.() -> Boolean
    ): Int {

        println("calling $description...")
        val success = KERNEL_32.nativeFunc()
        val errorCode = KERNEL_32.GetLastError()
        println("success=$success, code=$errorCode")
        return if (success || errorCode in otherAllowedCodes) errorCode
                else throw RuntimeException("$description failed with code=$errorCode")
    }
}

private fun buildPIDIndex(): Map<PID, List<PID>> {
    val processEntry: Tlhelp32.PROCESSENTRY32 = Tlhelp32.PROCESSENTRY32.ByReference() //dwsize set by jna.platform!
    val snapshotHandle: WinNT.HANDLE = KERNEL_32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))

    val childIDsByParent = TreeMap<PID, ArrayList<PID>>()
    if (KERNEL_32.Process32First(snapshotHandle, processEntry)) {
        do {
            val pid = processEntry.th32ProcessID.toInt()
            val ppid = processEntry.th32ParentProcessID.toInt()

            //to get a handle I think we need OpenProcess

            if (pid != 0) {
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