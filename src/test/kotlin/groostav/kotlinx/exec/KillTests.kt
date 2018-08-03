package groostav.kotlinx.exec

import assertThrows
import completableScriptCommand
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.toList
import org.junit.Test
import promptScriptCommand
import kotlin.test.*

class KillTests {


    @Test fun `when killing a process should exit without finishing`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync {
            command = completableScriptCommand()
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
        assertThrows<UnsupportedOperationException> { runningProcess.exitCode.await() }
    }
}

fun testing(any: Any){
    val x = 4;
}