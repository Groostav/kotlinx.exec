package groostav.kotlinx.exec

import assertNotListed
import emptyScriptCommand
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test

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