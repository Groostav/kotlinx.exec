package groostav.kotlinx.exec

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Test

@InternalCoroutinesApi
class ProcessIDGeneratorTests {

    @Test fun `when attempting to get PID for dead process should succeed`() = runBlocking<Unit> {
        //setup
        val deadProc = execAsync { command = emptyScriptCommand() }
        deadProc.join()

        //act
        val pid = deadProc.processID

        //assert
        assertNotListed(pid)
    }
}