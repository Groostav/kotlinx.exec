package groostav.kotlinx.exec

import Catch
import assertThrows
import completableScriptCommand
import emptyScriptCommand
import errorAndExitCodeOneCommand
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.toList
import org.junit.Test
import promptScriptCommand
import java.util.*
import kotlin.test.*

class JoinAwaitAndKillTests {

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
        assertThrows<CancellationException> { runningProcess.exitCode.await() }
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
        assertEquals(listOf("exitCodeJoin", "aggregateChannelJoin", "procJoin"), results)
    }

    @Test fun `when calling join twice shouldnt deadlock`() = runBlocking {
        //setup
        val runningProcess = execAsync {
            command = emptyScriptCommand()
        }

        //act
        runningProcess.join()
        runningProcess.join()

        //assert
        assertTrue(runningProcess.exitCode.isCompleted)
        assertFalse(runningProcess.exitCode.isActive)

        // TODO: I'd like this, but elizarov's own notes say its not a requirement
        // https://stackoverflow.com/questions/48999564/kotlin-wait-for-channel-isclosedforreceive
//        assertTrue(runningProcess.isClosedForReceive)
    }

    @Test fun `when async running process with unexpected exit code should exit appropriately`() = runBlocking {
        //setup
        val runningProcess = execAsync {
            command = errorAndExitCodeOneCommand()
        }

        //act
        val joinResult = runningProcess.join()                                                      // exits normally
        val exitCodeResult = Catch<InvalidExitValueException> { runningProcess.exitCode.await() }   // throws exception
        val aggregateChannelList = runningProcess.toList()                                          // produces list with exit code
        val errorChannel = runningProcess.standardError.toList()                                    // exits normally
        val stdoutChannel = runningProcess.standardOutput.toList()                                  // exits normally

        //assert
        assertEquals(Unit, joinResult)
        assertNotNull(exitCodeResult)
        assertTrue(exitCodeResult is InvalidExitValueException)
        assertEquals(aggregateChannelList.map { it::class.simpleName }.first(), StandardError::class.simpleName)
        assertEquals(aggregateChannelList.last(), ExitCode(1))
        assertTrue("Script is exiting with code 1" in errorChannel.joinToString(""))
        assertEquals(listOf(), stdoutChannel)
    }

    @Test fun `when synchronously running process with unexpected exit code should exit appropriately`() = runBlocking {
        //setup & act
        val invalidExitValue = Catch<InvalidExitValueException> {
            exec {
                command = errorAndExitCodeOneCommand()
            }
        }

        //assert
        assertNotNull(invalidExitValue)
        assertEquals(errorAndExitCodeOneCommand(), invalidExitValue!!.command)
        assertEquals(1, invalidExitValue.exitValue)
    }
}


