package groostav.kotlinx.exec

import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import kotlinx.coroutines.experimental.CoroutineName
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch

internal class WindowsProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    init {
        //normally we warn about java-9 here, but it doesnt support kill gracefully...
        //TODO how do we reconcile the reflection hack with kill gracefully on java 10+?
    }

    companion object: ProcessControlFacade.Factory {
        override fun create(process: Process, pid: Int) = supportedIf(Platform.isWindows()) { WindowsProcessControl(process, pid) }
    }

    override fun tryKillGracefullyAsync(includeDescendants: Boolean): Supported<Unit> {

        //so, zero-turnaround uses this strategy,
        // I'm kinda mad that jna.platform..Kernel32 wont give me this functionality!
        // i feel like there _must_ be a more elegant way,
        // but smarter people than me came up with this strategy...
        // maybe COM objects into WMIC? https://docs.microsoft.com/en-us/windows/desktop/wmisdk/creating-wmi-clients
        // how does the .net runtime do it? port that to java?

        var command = listOf("taskkill")
        if(includeDescendants) command += "/T"
        command += listOf("/PID", "$pid")

        launch(Unconfined + CoroutineName("process(PID=$pid).killGracefully")) {
            execAsync { this.command = command }.consumeEach { trace { it.formattedMessage } }
        }

        return Supported(Unit)
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Supported<Unit> {

        var command = listOf("taskkill")
        if(includeDescendants) command += "/T"
        command += "/F"
        command += listOf("/PID", "$pid")

        launch(Unconfined + CoroutineName("process(PID=$pid).killForcefully")) {
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

internal class WindowsReflectiveNativePIDGen(private val process: Process): ProcessIDGenerator {

    init {
        if(JavaVersion >= 9) trace { "WARN: using Windows reflection-based PID generator on java-9" }
    }

    companion object: ProcessIDGenerator.Factory {
        override fun create(process: Process) = supportedIf(JavaProcessOS == ProcessOS.Windows){
            WindowsReflectiveNativePIDGen(process)
        }
    }

    private val field = process::class.java.getDeclaredField("handle").apply { isAccessible = true }

    override val pid: Supported<Int> by lazy {

        val handlePeer = field.getLong(process)
        val handle = WinNT.HANDLE(Pointer.createConstant(handlePeer))
        val pid = Kernel32.INSTANCE.GetProcessId(handle)

        Supported(pid)
    }
}

