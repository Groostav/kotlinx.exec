package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.junit.Test
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@InternalCoroutinesApi
class KillTests {

    @Test
    fun `when killing a process should exit without finishing`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync {
            command = hangingCommand()
        }

        //act
        val completedAfter100ms = withTimeoutOrNull(100) { runningProcess.join() } != null
        runningProcess.kill()

        //assert
        assertFalse(completedAfter100ms)
        assertTrue(runningProcess.isCompleted)
        assertNotListed(runningProcess.processID)
    }

    @Test fun `when cancelling the parent job of a sub-process should kill child process`() = runBlocking<Unit>{

        // as a correllary to the above test, when cancelling the parent scope,
        // we emit a `kill` command to the process.

        var id: Int? = null
        val jobThatSpawnsSubProcess = launch {
            val proxy = this.execAsync {
                command = hangingCommand()
            }

            id = proxy.processID
        }

        delay(100) // cant call `jobThatSpawnsProcess.join()`
        // because it never finishes, because `hangingCommand` never exits

        jobThatSpawnsSubProcess.cancel()

        assertNotListed(id!!)
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
        val messages = result.await()
        assertEquals(2, messages.size)
        assertEquals(StandardOutputMessage("Hello!"), messages.first())
        assertNotEquals(ExitCode(0), messages.last())
        assertThrows<CancellationException> { runningProcess.await() }
        assertNotListed(runningProcess.processID)
    }

    @Test fun `when killing shell process tree gently should properly end all descendants`() = runBlocking<Unit> {

        //setup
        val pidRegex = Pattern.compile("PID=(?<pid>\\d+)")

        val runningProcess = execAsync {
            command = forkerCommand()
            includeDescendantsInKill = true
            gracefulTimeoutMillis = 99999999999
        }

        delay(30)

        val pidsFuture = CompletableDeferred<List<Int>>()

        GlobalScope.launch {
            runningProcess
                    .map { it.also { trace { it.formattedMessage }}}
                    .map { pidRegex.matcher(it.formattedMessage) }
                    .filter { it.find() }
                    .map { it.group("pid")?.toInt() ?: TODO() }
                    .fold(emptyList<Int>()){ accum, nextPID ->
                        trace { "found pid=$nextPID" }
                        (accum + nextPID).also {
                            if(it.size == 3){
                                pidsFuture.complete(it)
                            }
                        }
                    }
        }

        //act
        val pids = pidsFuture.await()
        runningProcess.kill()

        //assert
        assertEquals(3, pids.size)
        assertNotListed(*pids.toIntArray())

//        TODO("I saw this one flap!")

//        fail("this test passes on linux when I dont include the kill-child implementation, so my oracle's broken :sigh:")
        // blah, running forker-compose-up.sh from command line, then ctrl + Z, then ps, then kill -9 (parent), then ps
        // notice that all the child processes are dead. clearly I dont know enough about parent-child process relationships.
        // it seems that kill -9 in this cercomstance is giving me the "end process tree" behaviour I wanted.
    }


    @Test fun `when calling kill forcefully should end process and synchronize on its finish`() = runBlocking<Unit>{

        //setup
        val process = execAsync {
            command = hangingCommand()
        }

        //act
        process.kill()

        //assert
        assertNotListed(process.processID)
        assertTrue(process.isCompleted)
        assertFalse(process.isClosedForReceive) //still has data that can be read out.
        assertTrue(process.isClosedForSend)
    }

    @Test fun `when attempting to kill interruptable script should properly interrupt`() = runBlocking<Unit>{
        //setup
        val process = execAsync {
            command = interruptableHangingCommand()
            expectedOutputCodes = setOf(42)
            gracefulTimeoutMillis = 3000
        }
        delay(1000)
        launch { process.kill() }

        //act
        val result = process.await()

        //assert
        assertEquals(result, 42)
    }

    @Test fun `when attempting to kill unstarted process should quietly do nothing`(): Unit = runBlocking<Unit> {
        //setup
        val unstartedProcess = execAsync(CoroutineStart.LAZY) {
            command = interruptableHangingCommand()
        }

        //act
        unstartedProcess.kill()

        //assert
        unstartedProcess.apply {
            assertEquals(Unit, unstartedProcess.join())
            assertThrows<CancellationException> { unstartedProcess.await() }
            assertTrue(isCompleted)
            assertTrue(isCancelled)
            assertTrue(isClosedForReceive) //still has data that can be read out.
            assertTrue(isClosedForSend)
        }
    }
}