package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.dropWhile
import kotlinx.coroutines.channels.map
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test

@InternalCoroutinesApi
class WindowsFixture {

    companion object {
        @BeforeClass @JvmStatic fun assumeWindows() = Assume.assumeTrue(Platform.isWindows())
    }

    @Test(timeout = 10_000) fun `when killing forcefully process should exit`() = runBlocking<Unit> {
        //setup
        val process = ProcessBuilder()
                .command(hangingCommand())
                .start()

        val windowsControl = WindowsProcessControl(0, process, process.pid().toInt())

        //act
        windowsControl.killForcefullyAsync(true)

        // assert --if it completes then we killed it.
        process.waitFor()
        assertNotListed(process.pid().toInt())
    }

    @Test(timeout = 10_000) fun `when killing powershell gracefully process should exit`() = runBlocking<Unit> {
        //setup
        val process = ProcessBuilder()
                .command(hangingCommand())
                .start()

        val windowsControl = WindowsProcessControl(10_000, process, process.pid().toInt())

        //act
        windowsControl.tryKillGracefullyAsync(false)

        // assert --if it completes then we killed it.
        process.waitFor()
        assertNotListed(process.pid().toInt())
    }

    @Test(timeout = 10_000) fun `when killing powershell and its descendents gracefully process should exit`() = runBlocking<Unit> {
        //setup
        val process = ProcessBuilder()
                .command(hangingCommand())
                .start()

        val windowsControl = WindowsProcessControl(10_000, process, process.pid().toInt())

        //act
        windowsControl.tryKillGracefullyAsync(true)

        // assert --if it completes then we killed it.
        process.waitFor()
        assertNotListed(process.pid().toInt())
    }

    @Test(timeout = 60_000) fun `when killing a process tree with very fluid child count should properly end all processes`() = runBlocking<Unit> {
        val process = execAsync { command = fluidProcessCommand() }

        fail; //this flaps.

        val jproc = ((process as ExecCoroutine).state as ExecCoroutine.State.Running).process
        val windowsControl = WindowsProcessControl(30_000, jproc, process.processID)

        val warmedUp = CompletableDeferred<Unit>()
        GlobalScope.launch {
            process.map {
                val x = it.formattedMessage
                System.err.println(x)
                if(it.formattedMessage == "warmed-up") warmedUp.complete(Unit) else Unit
            }
        }
        warmedUp.await()

        //act
        windowsControl.tryKillGracefullyAsync(true)

        //assert --again, if we compelte then we killed it.
        process.waitFor()
        assertNotListed(process.processID)
    }
}