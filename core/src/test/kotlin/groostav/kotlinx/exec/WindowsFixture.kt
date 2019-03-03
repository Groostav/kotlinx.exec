package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Ignore
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

    @Test(timeout = 30_000)
    fun `when killing a process tree with very fluid child count should properly end all processes`() = runBlocking<Unit> {
        val process = execAsync { command = fluidProcessCommand() }

        val jproc = ((process as ExecCoroutine).state as ExecCoroutine.State.Running).process
        val windowsControl = WindowsProcessControl(30_000, jproc, process.processID)

        for(message in process.nonCancelling()){
            if(message.formattedMessage == "warmed-up") break
        }

        //act
        windowsControl.tryKillGracefullyAsync(true)

        //assert --again, if we compelte then we killed it.
        process.waitFor()
        assertNotListed(process.processID)
    }
}

fun <T> ReceiveChannel<T>.nonCancelling(): ReceiveChannel<T> = GlobalScope.produce<T>{
    for(message in this@nonCancelling){
        send(message)
    }
    //if the return value is cancelled, that wont propagate to this@nonCancelling.
}