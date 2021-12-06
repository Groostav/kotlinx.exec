package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Test
import java.lang.RuntimeException
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalCoroutinesApi::class)
class CancelAndKillTests {

    @Test
    fun `when killing a process should suspend until process is terminated`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync(
            commandLine = hangingCommand()
        )

        //act
        val completedAfter100ms = withTimeoutOrNull(100) { runningProcess.join() } != null
        runningProcess.kill(0)

        //assert
        assertFalse(completedAfter100ms)
        assertTrue(runningProcess.isCompleted)
        assertNotListed(runningProcess.processID)
        assertEquals(listOf(ExitCode(0)), runningProcess.toList())
    }

    @Test
    fun `when cancelling a process should exit without finishing asynchronously`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync (
            commandLine = hangingCommand()
        )

        //act
        val completedAfter100ms = withTimeoutOrNull(100) { runningProcess.join() } != null
        runningProcess.kill()

        //assert
        assertFalse(completedAfter100ms)
        assertTrue(runningProcess.isCompleted)
        assertNotListed(runningProcess.processID)
    }

    @Test
//        (timeout = 20_000)
    fun `when cancelling the parent job of a sub-process should kill child process`() = runBlocking<Unit>{
//        fail; //im not sure whats holding this test open,
        // proxy.await() is cancelled,
        // it is firing a kill request
        // kill gently doesnt work so it kills with destroy() --which is odd actually
        // and then its just sorta left open; I would've thought that since it was in cancelling
        // it would complete as cancelled, but instead it just sorta hangs out.

        // as a correllary to the above test, when cancelling the parent scope,
        // we emit a `kill` command to the process.

        var pid: Long? = null
        val mutex = Mutex(locked = true)

        val jobThatSpawnsSubProcess = launch {
            val proxy = execAsync(
                commandLine = hangingCommand()
            )

            pid = proxy.processID
            mutex.unlock()

            withContext(NonCancellable){ proxy.join() }
            mutex.unlock()
        }

        //act
        mutex.lock() // cant call `jobThatSpawnsProcess.join()`
        // because it never finishes, because `hangingCommand` never exits
        jobThatSpawnsSubProcess.cancel(CancellationException("asdf", RuntimeException("blam!")))
//        waitForTerminationOf(pid!!)
        mutex.lock()

        //assert
        assertNotListed(pid!!)
    }


    @Test(timeout = 100_000)
    fun `when cancelling a process with another consumer should simply close the resulting channel for that consumer`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync(
            commandLine = promptScriptCommand(),
            includeDescendantsInKill = true,
            expectedOutputCodes = null,
            gracefulTimeoutMillis = 999_999 //timeout this test if graceful kill didnt succeed
            //oook.... so this test was written assuming that promptScriptCommand() would respond to ctrl + C<
            // but it doesnt appear to anymore?
        )

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
        assertEquals(listOf(StandardOutputMessage("Hello from ${runningProcess.processID}"), ExitCode(99)), result.await())
        assertEquals(99, runningProcess.await())
        assertNotListed(runningProcess.processID)
    }

    @Test fun `when killing parent of stand-alone child should continue to let stand alone child run`() = runBlocking<Unit> {
        val proc = execAsync(
            commandLine = singleParentSingleChildCommand() + "-WaitForExit",
            includeDescendantsInKill = false,
            gracefulTimeoutMillis = 10_000
        )

        val providedPIDs = proc
                .takeWhile { it.formattedMessage.let { Regex("(childPID=\\d+)|(parentPID=\\d+)").matches(it) } }
                .take(2)
                .toList()
                .associate { it.formattedMessage.split("=").let { it.first() to it.last().toLong() } }
        println("running with PIDs=$providedPIDs")

        //act
        GlobalScope.launch {
            proc.kill(obtrudeExitCode = 0)
            println("asdf")
        }
        proc.collect {
            System.err.println(it.formattedMessage)
        }

        //assert
        val runningPIDs = pollRunningPIDs()
        providedPIDs["parentPID"].let { assertTrue(it !in runningPIDs, "expected parentPID=$it not to be running, but it was")}
        providedPIDs["childPID"].let { assertTrue(it in runningPIDs, "expected childPID=$it to still be running, but it wasn't")}
    }

//    @Test(timeout=30_000)
    @Test
    fun `when killing shell process tree gently should properly end all descendants`() = runBlocking<Unit> {

        //setup
        val pidRegex = Pattern.compile("PID=(?<pid>\\d+)")

        val runningProcess = execAsync(
            commandLine = forkerCommand(),
            includeDescendantsInKill = true,
            gracefulTimeoutMillis = 99999999999
        )

        delay(30)

        val pids = runningProcess
//                .nonCancelling()
                .map { it.also { println(it.formattedMessage) }}
                .map { pidRegex.matcher(it.formattedMessage) }
                .filter { it.find() }
                .map { it.group("pid")?.toLong() ?: TODO() }
                .take(3)
                .map { nextPID ->
                    System.err.println("found pid=$nextPID")
                    nextPID
                }
                .toList()

        //act
        runningProcess.kill()

        //assert
        assertEquals(3, pids.size)
        assertNotListed(*pids.toLongArray())

        // blah, running forker-compose-up.sh from command line, then ctrl + Z, then ps, then kill -9 (parent), then ps
        // notice that all the child processes are dead. clearly I dont know enough about parent-child process relationships.
        // it seems that kill -9 in this cercomstance is giving me the "end process tree" behaviour I wanted.
    }


    @Test fun `when calling kill forcefully should end process and synchronize on its finish`() = runBlocking<Unit>{

        //setup
        val process = execAsync (
            commandLine = hangingCommand(),
            gracefulTimeoutMillis = 0L
        )

        //act
        process.kill(null)

        //assert
        assertNotListed(process.processID)
        assertTrue(process.isCompleted)
//        assertFalse(process.isClosedForReceive) //still has data that can be read out.
//        assertTrue(process.isClosedForSend)
    }

    @Test fun `when attempting to kill interruptable script should properly interrupt`() = runBlocking<Unit>{
        //setup
        val process = execAsync(
            commandLine = interruptableHangingCommand(),
            expectedOutputCodes = setOf(42),
            gracefulTimeoutMillis = 9_999_999_999
        )
        delay(3000)
        launch {
            process.kill(42)
        }

        //act
        val result = process.toList()
        val x = 4;
        process.join()
//        val result = process.await()

        //assert
        assertEquals(listOf(StandardOutputMessage("interrupted"), ExitCode(42)), result)
//        assertEquals(42, result)
    }

    @Test fun `when attempting to kill unstarted process should quietly do nothing`(): Unit = runBlocking<Unit> {
        //setup
        val unstartedProcess = execAsync(
            commandLine = emptyScriptCommand(),
            expectedOutputCodes = setOf(1),
            lazy = true
        )

        //act
        unstartedProcess.kill(1)

        //assert
        assertEquals(unstartedProcess.toList(), emptyList())
        assertEquals(1, unstartedProcess.await())
        assertTrue(unstartedProcess.isCompleted)
    }

    @Test fun `when attempting to get PID for a completed process should succeed`() = runBlocking<Unit> {
        //setup
        val deadProc = execAsync(commandLine = emptyScriptCommand())
        deadProc.join()

        //act
        val pid = deadProc.processID

        //assert
        assertNotListed(pid)
    }

    @Test fun `when killed before start should simply exit without starting`() = runBlocking<Unit> {
        //setup
        val proc = execAsync(commandLine = emptyScriptCommand(), lazy = true)

        //act
        proc.kill(0)

        //assert
        assertTrue(proc.isCompleted)
        assertEquals(0, proc.await())
        assertEquals(emptyList(), proc.toList())
    }


    @Test fun `when killing a script gracefully that can be interrupted should interrupt`(){
        TODO("in the retrofit im noticing that a lot of the kill gracefully code on powershell is failing")
        // write a test here that can killgracefully a powershell script to still actually do something.
        // maybe just write a powershell script with busy waiting? or a while(true) { sleep(1) } ?
    }
}
