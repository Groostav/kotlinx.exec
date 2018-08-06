package groostav.kotlinx.exec

import assertThrows
import completableScriptCommand
import emptyScriptCommand
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.toList
import org.junit.Test
import promptScriptCommand
import java.util.*
import kotlin.test.*

class KillTests {


    @Test fun `when killing a process should exit without finishing`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync {
            command = promptScriptCommand()
        }

        //act
        withTimeoutOrNull(10) { runningProcess.join() }
        if( ! runningProcess.isClosedForReceive){
            runningProcess.kill()
        }

        //assert
        assertTrue(runningProcess.exitCode.isCompleted)
        assertFalse(runningProcess.exitCode.isActive)
    }

    @Test fun `when cancelling a process with another consumer should simply close the resulting channel for that consumer`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync {
            command = promptScriptCommand()
        }

        //act
        val firstMessageReceived = CompletableDeferred<Unit>()
        val result = async<List<ProcessEvent>> {
            val first = runningProcess.receive()
            firstMessageReceived.complete(Unit)
            listOf(first) + runningProcess.toList()
        }
        firstMessageReceived.await()
        runningProcess.kill()

        //assert
        val actual = result.await()
        assertEquals(listOf(StandardOutput("Hello!")), actual)
        assertThrows<JobCancellationException> { runningProcess.exitCode.await() }
    }

    @Test fun `when exiting normally should perform orderly shutdown`() = runBlocking {
        //setup
        val process = execAsync { command = completableScriptCommand() }

        val results = Collections.synchronizedList(ArrayList<String>())

        //act
        val procJoin = launch { process.join(); results += "procJoin" }
        val exitCodeJoin = launch { process.exitCode.join(); results += "exitCodeJoin" }
        val aggregateChannelJoin = launch { process.toList(); results += "aggregateChannelJoin" }

        process.send("OK")
        process.close()


        procJoin.join(); exitCodeJoin.join(); aggregateChannelJoin.join()

        //assert
        assertEquals(listOf("procJoin", "exitCodeJoin", "aggregateChannelJoin"), results)
    }
}
