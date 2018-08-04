package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch

internal class WindowsProcessControl(val process: Process, val pid: Int): ProcessControlFacade {

    init {
        require(isAvailable)
        if(JavaVersion >= 9) trace { "WARN: using Windows Process Control on Java 9+" }
    }

    companion object: ProcessControlFacade.Factory {
        override val isAvailable: Boolean by lazy { Platform.isWindows() }
        override fun create(process: Process, pid: Int) = WindowsProcessControl(process, pid)
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

        launch(Unconfined) {
            execAsync { this.command = command }.consumeEach { trace { it.formattedMessage } }
        }

        return Supported(Unit)
    }

    override fun killForcefullyAsync(includeDescendants: Boolean): Supported<Unit> {

        var command = listOf("taskkill")
        if(includeDescendants) command += "/T"
        command += "/F"
        command += listOf("/PID", "$pid")

        launch(Unconfined) {
            execVoid { this.command = command }
        }

        return Supported(Unit)
    }
}

