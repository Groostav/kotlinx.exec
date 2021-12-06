package groostav.kotlinx.exec

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.*
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import com.sun.jna.platform.win32.WinDef.BOOL
import com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED
import com.sun.jna.platform.win32.WinError.ERROR_INVALID_HANDLE
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.flow.collect
import java.io.Closeable
import java.lang.ProcessBuilder.*
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.jvm.internal.FunctionReference
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.jvmName

internal typealias Win32PID = Int

//note this class may be preferable to the jep102 based class because kill gracefully (aka normally)
// isnt supported on windows' implementation of ProcessHandle.
@OptIn(InternalCoroutinesApi::class)
internal object WindowsProcessControl {

    val JavawLocation = System.getProperty("kotlinx.exec.javaw") ?: System.getProperty("java.home") + "/bin/javaw"

    /**
     * Attempts to send a CTRL_C signal to the parent console of this process, interrupting it.
     *
     * This library employs a fairly novel solution to graceful process termination,
     * avoiding `taskkill /PID` in favor of attempting to send a CTRL_C to the parent console window.
     *
     * Using `taskkill` (without /F) assumes a windows UI application
     * in that it sends it a WM_CLOSE signal to the target process.
     * This approach does not solicit any behaviour from cmd or powershell processes
     * --which are, I believe, the lions share of use cases for this library.
     * `WM_CLOSE` signals typically cause GUI applications to launch a
     * "do you want to save or close?" dialog,
     * which, fundamentally, means the process won't close programmatically
     * (they will wait for user input to such a dialog!)
     *
     * Instead the solution here is to create a new process that attaches itself to the console
     * used by the target process, and emit a "CTRL_C" signal to that process.
     * This has the desired effect of stopping cmd and powershell scripts,
     * in addition to causing any appropriate powershell `Finally {}` blocks to fire.
     *
     * If the app is a GUI application (determined when AttachConsole returns INVALID_HANDLE)
     * we hit it with a simple taskkill /PID 1234 (which emites a WM_CLOSE, which isn't likely to work)
     */
    fun tryKillGracefullyAsync(
        tracing: CoroutineTracer,
        selfPID: Long,
        target: Process,
        includeDescendants: Boolean,
        deadline: Long,
    ): Boolean {

        val tracing = tracing.appendName("killGracefully")

        val javaw = Paths.get(JavawLocation)
        if ( ! javaw.isAbsolute) {
            // user could specify a relative javaw, and put it on $PATH/LD_LIBRARY_PATH,
            // meaning we simply cant validate it eagerly.
            // rather annoying that both System and JNA dont expose a "can i find this executable" option.
            if ( ! Files.exists(javaw.parent)) {
                throw UnsupportedOperationException("cannot find javaw executable at 'kotlinx.exec.javaw=$JavawLocation'")
            }
        }

        val targetPID = target.toHandle().pid()

        // oook, given that processes can end asynchronously and their PIDs can be re-used
        // how do we make sure that the PID in the PID index is actually one of our children?
        // one tool that we have is that when we acquire a HANDLE with GetProcess it 'locks' that PID,
        // in other words creating a Win32ProcessProxy for a PID means that PID cannot be re-used,
        // even if the process represented by that proxy ends,
        // thus we have a way to 'lock' the process tree.

        // so our strategy, similar to a cas-loop
        // do {
        //   val previous = pidIndex()
        //   lock(previous)
        //   val recent = pidIndex()
        // while(recent != previous)
        //
        // the idea is that we're going to read a copy of the process tree,
        // lock it, and then read it again. If it didnt change between the two reads,
        // then the lock we acquired is good for the most recent process tree.

        // this smells an aweful lot like a doubly-checked lock...
        // isnt that an antipattern?

        var previousTree: Win32ProcessProxy? = null
        var recentTree: Win32ProcessProxy? = null

        do try {
            if (System.currentTimeMillis() > deadline) {
                tracing.trace { "timed-out building process tree for $targetPID" }
                recentTree?.close()
                return false
            }

            val pidAdjacencyList: Map<Win32PID, List<Win32PID>> = buildPIDIndex()

            recentTree = toProcessTree(pidAdjacencyList, targetPID.toWin32PID(), includeDescendants)

            if(recentTree == null){
                tracing.trace { "pid $targetPID exited while being killed" }
                return false
            }

            if(recentTree.pid !in (pidAdjacencyList[selfPID.toWin32PID()] ?: emptyList())) {
                // the target PID is not a child of this PID,
                // indicating either the process quit early,
                // or the user made an error.
                tracing.trace { "PID $targetPID not a child of this process $selfPID" }
                recentTree.close()
                return false
            }
        }
        finally {
            previousTree?.close()
            previousTree = recentTree
        }
        while(recentTree != previousTree)
        // if the two trees are structurally equal => we've acquired a correct snap-shot of our process tree.
        // new successor processes may have spawned. That is not covered here.

        val actualTree = recentTree!!

        actualTree.use {

            tracing.trace { "comitted to killing process-tree $actualTree" }

            // some concerns:
            // whats to stop this from recursing? what if we cancel the interruptor? what if we cancel that?
            //   -> need to support an "atomic" process to use this library.
            //      In the mean time the fire-and-forget j.l.ProcessBuilder will suffice.
            //   -> MITIGATED: use "NonCancellable" dispatcher
            // can we re-use the same process to kill all of them? (ie killer -pids 123,4567,8901 to kill 3 PIDs at once)
            //   -> probably: TBD.
            // also: what if it spawns a new process while its being interrupted??
            //   -> not this libraries problem. If that does happen its entirely likely the
            //      spawned sub-process was some kind of cleanup, possibly initiated by a finally{} block
            //      so I think its best to leave it running.


            val pids: String = actualTree.toSequence().joinToString(",") { it.pid.toString() }
            val name = "siginter -pid $pids"

            // this cant be that durable...
            val commandLine = listOf(
                javaw.toAbsolutePath().toString(),
                "-cp", System.getProperty("java.class.path"),
                PoliteLeechKiller::main.instanceTypeName,
                "-pid", pids
            )

            val reaperCode = if(DEBUGGING_GRACEFUL_KILL){
                ungracefullyKillGracefully(commandLine)
            }
            else runBlocking(Dispatchers.IO) {
                val remainingTime = deadline - System.currentTimeMillis()

                if(remainingTime > 0) withTimeoutOrNull(remainingTime) {
                    val reaper = execAsync(
                        commandLine = commandLine.toList(),
                        gracefulTimeoutMillis = 0, //avoid recursion
                        expectedOutputCodes = null
                    )
                    reaper.collect { message: ProcessEvent ->
                        tracing.trace { "$name: ${message.formattedMessage}" }
                    }
                    reaper.await()
                }
                else 2
            }

            tracing.trace { "graceful returned exitCode=${reaperCode ?: "[timed-out]"}" }
            return reaperCode == 0
        }
    }

    //also, implementing a WindowsListener.addExitCodeHandle with RegisterWaitForSingleObject function sounds good,
    // https://docs.microsoft.com/en-us/windows/desktop/api/winbase/nf-winbase-registerwaitforsingleobject
    // but it looks like that just punts the problem from the jvm into kernel 32, which still uses the same
    // (blocking thread) strategy.
    // => dont bother, no matter the API we're still polling the bastard.

    private const val DEBUGGING_GRACEFUL_KILL: Boolean = true
    init { if(DEBUGGING_GRACEFUL_KILL) System.err.println("DEBUGGING_GRACEFUL_KILL enabled") }
}

fun ungracefullyKillGracefully(commandLine: List<String>): Int = ProcessBuilder()
    .command(commandLine)
    .redirectError(Redirect.INHERIT)
    .redirectOutput(Redirect.INHERIT)
    .start()
    .waitFor()

private val KFunction<*>.instanceTypeName: String get() {
    return ((this as FunctionReference).boundReceiver::class as KClass<*>).jvmName
}

internal class Win32ProcessProxy(
    val pid: Win32PID,
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

    //returns a depth-first sequence
    fun toSequence(): Sequence<Win32ProcessProxy> = sequenceOf(this) + children.asSequence().flatMap { it.toSequence() }

    override fun equals(other: Any?): Boolean =
        other is Win32ProcessProxy && other.pid == this.pid && other.children == this.children

    override fun hashCode(): Int = pid xor children.hashCode()
    override fun toString(): String = "Win32ProcessProxy(pid=$pid, children=$children)"

}

private fun toProcessTree(pidAdjacencyList: Map<Win32PID, List<Win32PID>>, rootPid: Win32PID, includeDescendants: Boolean): Win32ProcessProxy? {

    // note: we must build the tree from the root down
    // else we may leak child references

    val handle = KERNEL_32.OpenProcess(WinNT.SYNCHRONIZE, false, rootPid)

    if (! includeDescendants) return Win32ProcessProxy(rootPid, handle, emptyList())

    if(handle == KERNEL_32.NULL_HANDLE) {
        // implies child exited after we got its PID but before we got its handle
        return null
    }

    val children = pidAdjacencyList[rootPid]
        ?.map { toProcessTree(pidAdjacencyList, it, includeDescendants) }
        ?.filterNotNull()
        ?: emptyList()

    return Win32ProcessProxy(rootPid, handle, children)
}

private typealias PHANDLER_ROUTINE = Pointer?

interface KERNEL_32: Kernel32 {

    // https://docs.microsoft.com/en-us/windows/console/setconsolectrlhandler
    fun SetConsoleCtrlHandler(handler: PHANDLER_ROUTINE, add: BOOL): Boolean

    companion object: KERNEL_32 by Native.load("kernel32", KERNEL_32::class.java, W32APIOptions.DEFAULT_OPTIONS){
        val NULL_HANDLE = WinNT.HANDLE(null)
    }
}

private const val SUCCESS = 0
private const val UNKNOWN_ERROR = 1

private fun Long.toWin32PID(): Win32PID = toInt()

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
        println("running! args=${args.joinToString("', '", "'", "'")}")
        require(args.size == 2) { "expected args: -pid <pid_int>"}
        require(args[0] == "-pid") { "expected args: -pid <pid_int>, but args[0] was ${args[0]}"}
        val pids = args[1].toIntListOrNull()
        require(pids != null) { "expected -pid as integer value, but was ${args[1]}"}

        //https://stackoverflow.com/questions/1229605/is-this-really-the-best-way-to-start-a-second-jvm-from-java-code
        // attaching a breakpoint: https://www.youtube.com/watch?v=fBGWtVOKTkM
        // this code is run in another vm/process!

        val result = killAllByInterrupting(pids)
        System.exit(result)
    }

    private fun killAllByInterrupting(pids: List<Int>): Int {

        printExecutePrintAndThrowIfFailed("Kernel32.FreeConsole", otherAllowedCodes = null, nativeFunc = KERNEL_32::FreeConsole)

        for(pid in pids) try {
            println("interrupting $pid:")

            printExecutePrintAndThrowIfFailed("Kernel32.SetConsoleCtrlHandler(NULL, TRUE)") {
                SetConsoleCtrlHandler(Pointer.NULL, BOOL(true))
            }

            val attachResult = printExecutePrintAndThrowIfFailed(
                "Kernel32.AttachConsole($pid)",
                otherAllowedCodes = arrayOf(ERROR_INVALID_HANDLE, ERROR_ACCESS_DENIED)
            ) {
                AttachConsole(pid)
            }
            // as per https://docs.microsoft.com/en-us/windows/console/attachconsole#remarks
            // this means the process doesn't have a console,
            // so we send it a WM_CLOSE with taskkill
            println("starting taskkill /PID $pid")
            java.lang.ProcessBuilder()
                .command("taskkill", "/PID", "$pid")
                //note: children are handled by the invoker of this program/
                .redirectError(Redirect.INHERIT)
                .redirectOutput(Redirect.INHERIT)
                .start()
                .waitFor()

            // note, this might be a place to use kotlin native...
            // https://stackoverflow.com/questions/38081028/using-createprocess-and-exit-close-the-opened-application/38081764#38081764
            // problem is; afaik, there is no nice mechanism to call kotlin-native from kotlin-jvm.

            if (attachResult == ERROR_ACCESS_DENIED){
                // already attached... ?
            }

            run {
                val eventName = "CTRL_C_EVENT" //CTRL_BREAK may cause powershell to start debugging
                val eventCode = Kernel32.CTRL_C_EVENT

                println("submitting $eventName to pid=$pid")
                printExecutePrintAndThrowIfFailed("Kernel32.GenerateConsoleCtrlEvent($eventName, 0)") {
                    GenerateConsoleCtrlEvent(eventCode, 0)
                }
            }

            // first attempt at dislodging Read-Host:
            // synthetically give an enter to standard-in:
//            val ENTER_KEY_SCANCODE = 0x1C.toWORD()
//            val VK_RETURN = 0x0D.toWORD()
//            val KEYEVENTF_KEYUP = 0x02.toDWORD()
//
//            @Suppress("UNCHECKED_CAST") //old JNA code; works pre-java-4
//            val inputs = WinUser.INPUT().toArray(2) as Array<WinUser.INPUT>
//
//            inputs[0].apply {
//                type = WinUser.INPUT.INPUT_KEYBOARD.toDWORD()
//                input.ki = WinUser.KEYBDINPUT().apply {
//                    wVk = VK_RETURN
//                }
//            }
//            inputs[1].apply {
//                type = WinUser.INPUT.INPUT_KEYBOARD.toDWORD()
//                input.ki = WinUser.KEYBDINPUT().apply {
//                    wVk = VK_RETURN
//                    dwFlags = KEYEVENTF_KEYUP
//                }
//            }
//
//            print("User32.INSTANCE.SendInput(${inputs.size}, $inputs, ${inputs[0].size()})...")
//            val writtenBytes = User32.INSTANCE.SendInput(WinDef.DWORD(inputs.size.toLong()), inputs, inputs[0].size())
//            println("write-count=${writtenBytes.low.toInt()}")
            
            println("done interrupting $pid")
        }
        catch (ex: Throwable) {
            ex.printStackTrace()
            return UNKNOWN_ERROR
        }
        finally {
            printExecutePrintAndThrowIfFailed("Kernel32.FreeConsole", otherAllowedCodes = null){ FreeConsole() }
        }

        return SUCCESS
    }

    private fun printExecutePrintAndThrowIfFailed(
        description: String,
        otherAllowedCodes: Array<out Int>? = emptyArray(),
        nativeFunc: KERNEL_32.() -> Boolean
    ): Int {
        print("calling $description... ")
        val success = KERNEL_32.nativeFunc()
        val errorCode = if(success) 0 else KERNEL_32.GetLastError()
        println("success=$success, code=$errorCode")
        return if (success || otherAllowedCodes == null || errorCode in otherAllowedCodes) errorCode
        else throw RuntimeException("$description failed with code=$errorCode")
    }

    private fun String.toIntListOrNull(): List<Int>? =
        split(",").map { it.trim().toIntOrNull() }.takeIf { null !in it }?.filterNotNull()
}

private fun buildPIDIndex(): Map<Win32PID, List<Win32PID>> {

    val snapshotHandle: WinNT.HANDLE = KERNEL_32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))

    val processEntry: Tlhelp32.PROCESSENTRY32 = Tlhelp32.PROCESSENTRY32.ByReference() //dwsize set by jna.platform!
    val childIDsByParent = TreeMap<Win32PID, ArrayList<Win32PID>>()

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

private fun Int.toDWORD() = WinDef.DWORD(this.toLong())
private fun Int.toWORD() = WinDef.WORD(this.toLong())