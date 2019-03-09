package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.junit.Test
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancelAndKillTests {

    @Test
    fun `when killing a process should suspend until process is terminated`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync {
            command = hangingCommand()
        }

        //act
        val completedAfter100ms = withTimeoutOrNull(100) { runningProcess.join() } != null
        runningProcess.kill(99)

        //assert
        assertFalse(completedAfter100ms)
        assertTrue(runningProcess.isCompleted)
        assertNotListed(runningProcess.processID)
        assertEquals(ExitCode(99), runningProcess.last())
    }

    @Test
    fun `when cancelling a process should exit without finishing asynchronously`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync {
            command = hangingCommand()
        }

        //act
        val completedAfter100ms = withTimeoutOrNull(100) { runningProcess.join() } != null
        runningProcess.cancel()
        waitForTerminationOf(runningProcess.processID) //note that cancel() does not synchronize on the job finishing.

        //assert
        assertFalse(completedAfter100ms)
        assertTrue(runningProcess.isCompleted)
        assertNotListed(runningProcess.processID)
    }

    @Test fun `when cancelling the parent job of a sub-process should kill child process`() = runBlocking<Unit>{

        // as a correllary to the above test, when cancelling the parent scope,
        // we emit a `kill` command to the process.

        var pid: Int? = null
        val jobThatSpawnsSubProcess = launch {
            val proxy = this.execAsync {
                command = hangingCommand()
            }

            pid = proxy.processID
        }

        //act
        delay(100) // cant call `jobThatSpawnsProcess.join()`
        // because it never finishes, because `hangingCommand` never exits
        jobThatSpawnsSubProcess.cancel()
        waitForTerminationOf(pid!!)

        //assert
        assertNotListed(pid!!)
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
        runningProcess.kill(99)

        //assert
        assertEquals(listOf(StandardOutputMessage("Hello!"), ExitCode(99)), result.await())
        assertThrows<CancellationException> { runningProcess.await() }
        assertNotListed(runningProcess.processID)
    }

    @Test fun `when killing parent of stand-alone child should continue to let stand alone child run`() = runBlocking<Unit> {
        val proc = execAsync {
            command = singleParentSingleChildCommand() + "-WaitForExit"
            includeDescendantsInKill = false
            gracefulTimeoutMillis = 0
        }

        val providedPIDs = proc
                .nonCancelling()
                .takeWhile { it.formattedMessage.let { Regex("(childPID=\\d+)|(parentPID=\\d+)").matches(it) } }
                .take(2)
                .toList()
                .associate { it.formattedMessage.split("=").let { it.first() to it.last().toInt() } }
        println("running with PIDs=$providedPIDs")

        //act
        GlobalScope.launch { proc.kill() }
        GlobalScope.launch {
            proc.nonCancelling().consumeEach { System.err.println(it.formattedMessage) }
        }
        proc.join()

        //assert
        val runningPIDs = pollRunningPIDs()
        providedPIDs["parentPID"].let { assertTrue(it !in runningPIDs, "expected parentPID=$it not to be running, but it was")}
        providedPIDs["childPID"].let { assertTrue(it in runningPIDs, "expected childPID=$it to still be running, but it wasn't")}
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


        val pids = runningProcess
                .nonCancelling()
                .map { it.also { trace { it.formattedMessage }}}
                .map { pidRegex.matcher(it.formattedMessage) }
                .filter { it.find() }
                .map { it.group("pid")?.toInt() ?: TODO() }
                .take(3)
                .map { nextPID ->
                    System.err.println("found pid=$nextPID")
                    nextPID
                }
                .toList()

        //act
        runningProcess.kill(null)

        //assert
        assertEquals(3, pids.size)
        assertNotListed(*pids.toIntArray())

        // blah, running forker-compose-up.sh from command line, then ctrl + Z, then ps, then kill -9 (parent), then ps
        // notice that all the child processes are dead. clearly I dont know enough about parent-child process relationships.
        // it seems that kill -9 in this cercomstance is giving me the "end process tree" behaviour I wanted.
    }


    @Test fun `when calling kill forcefully should end process and synchronize on its finish`() = runBlocking<Unit>{

        //setup
        val process = execAsync {
            command = hangingCommand()
            gracefulTimeoutMillis = 0L
        }

        //act
        process.kill(null)

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
            gracefulTimeoutMillis = 9999999999
        }
        delay(3000)
        launch { process.kill(null) }

        //act
        val result = process.toList()
        process.join()
//        val result = process.await()

        //assert
        assertEquals(listOf(StandardOutputMessage("interrupted"), ExitCode(42)), result)
//        assertEquals(42, result)
    }

    @Test fun `when attempting to kill unstarted process should quietly do nothing`(): Unit = runBlocking<Unit> {
        //setup
        val unstartedProcess = execAsync(CoroutineStart.LAZY) {
            command = interruptableHangingCommand()
        }

        //act
        unstartedProcess.kill(null)

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

    @Test fun `when attempting to get PID for a completed process should succeed`() = runBlocking<Unit> {
        //setup
        val deadProc = execAsync { command = emptyScriptCommand() }
        deadProc.join()

        //act
        val pid = deadProc.processID

        //assert
        assertNotListed(pid)
    }
}
