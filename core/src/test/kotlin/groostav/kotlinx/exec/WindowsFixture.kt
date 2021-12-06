package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import java.time.Instant
import kotlin.streams.toList

@InternalCoroutinesApi
class WindowsFixture {

    companion object {
        @BeforeClass @JvmStatic fun assumeWindows() = Assume.assumeTrue(Platform.isWindows())
    }

    @Ignore
    @Test(timeout = 10_000) fun `when killing forcefully process should exit`() = runBlocking<Unit> {
        TODO("uh, I figured this could be kept because we were ablet to hack graceful termination into windows" +
                "which was helpful for asdf")
//        //setup
//        val process = ProcessBuilder()
//                .command(hangingCommand())
//                .start()
//
//        val windowsControl = WindowsProcessControl(0, process, process.pid().toInt())
//
//        //act
//        windowsControl.killForcefullyAsync(true)
//
//        // assert --if it completes then we killed it.
//        process.waitFor()
//        assertNotListed(process.pid().toInt())
    }

    @Ignore
    @Test(timeout = 10_000) fun `when killing powershell gracefully process should exit`() = runBlocking<Unit> {
        TODO()
//        //setup
//        val process = ProcessBuilder()
//                .command(hangingCommand())
//                .start()
//
//        val windowsControl = WindowsProcessControl(10_000, process, process.pid().toInt())
//
//        //act
//        windowsControl.tryKillGracefullyAsync(false)
//
//        // assert --if it completes then we killed it.
//        process.waitFor()
//        assertNotListed(process.pid().toInt())
    }

    @Ignore
    @Test(timeout = 10_000) fun `when killing powershell and its descendents gracefully process should exit`() = runBlocking<Unit> {
        TODO()
//        //setup
//        val process = ProcessBuilder()
//                .command(hangingCommand())
//                .start()
//
//        val windowsControl = WindowsProcessControl(10_000, process, process.pid().toInt())
//
//        //act
//        windowsControl.tryKillGracefullyAsync(true)
//
//        // assert --if it completes then we killed it.
//        process.waitFor()
//        assertNotListed(process.pid().toInt())
    }

    @Test(timeout = 30_000)
    fun `when killing a process tree with very fluid child count should properly end all processes`() = runBlocking<Unit> {
        val process = execAsync(
            commandLine = fluidProcessCommand(),
            includeDescendantsInKill = true
        ) as ExecCoroutine

//        val jproc = ((process as ExecCoroutine).state as ExecCoroutine.State.Running).process

        val continueMessage = process.first { message ->
            message.formattedMessage == "warmed-up"
        }

        val childPIDs = ProcessHandle.of(process.processID).get().children().map { it.pid() }.toList()

        //act
        WindowsProcessControl.tryKillGracefullyAsync(
            CoroutineTracer("test"),
            ProcessHandle.current().pid(),
            process.process,
            true,
            System.currentTimeMillis() + 30_000
        )

        //assert --again, if we complete then we killed it.
        process.await()
        assertNotListed(*childPIDs.toLongArray())
    }
}