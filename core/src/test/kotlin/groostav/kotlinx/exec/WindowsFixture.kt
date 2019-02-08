package groostav.kotlinx.exec

import com.sun.jna.Platform
import io.mockk.mockk
import kotlinx.coroutines.InternalCoroutinesApi
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test

@InternalCoroutinesApi
class WindowsFixture {

    companion object {
        @BeforeClass fun assumeWindows() = Assume.assumeTrue(Platform.isWindows())
    }

    @Test(timeout = 10_000) fun `when killing forcefully process should exit`(){
        //setup
        val process = ProcessBuilder()
                .command(hangingCommand())
                .start()

        val windowsControl = WindowsProcessControl(0, process, process.pid().toInt())

        //act
        windowsControl.killForcefullyAsync(true)

        // assert --if it completes then we killed it.
        process.waitFor()
    }

    @Test(timeout = 10_000) fun `when killing powershell gracefully process should exit`(){
        //setup
        val process = ProcessBuilder()
                .command(hangingCommand())
                .start()

        val windowsControl = WindowsProcessControl(9_000, process, process.pid().toInt())

        //act
        windowsControl.tryKillGracefullyAsync(false)

        // assert --if it completes then we killed it.
        process.waitFor()
    }

    @Test(timeout = 10_000) fun `when killing powershell and its descendents gracefully process should exit`(){
        //setup
        val process = ProcessBuilder()
                .command(hangingCommand())
                .start()

        val windowsControl = WindowsProcessControl(9_000, process, process.pid().toInt())

        //act
        windowsControl.tryKillGracefullyAsync(true)

        // assert --if it completes then we killed it.
        process.waitFor()
    }
}