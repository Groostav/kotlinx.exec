package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.filter
import kotlinx.coroutines.channels.map
import kotlinx.coroutines.channels.take
import kotlinx.coroutines.channels.toList
import org.junit.Ignore
import org.junit.Test
import java.util.*
import java.util.regex.Pattern
import kotlin.test.*

class JoinAwaitAndKillTests {


    @Test fun `when command returns allowed nonzero exit code should return normally`() = runBlocking<Unit>{

        // because '1' is an expected code, and the script exited with code 1, we see that as a regular return value,
        // rather than a thrown UnexpectedExitCode exception
        val (_, code) = exec {
            command = errorAndExitCodeOneCommand()
            expectedOutputCodes = setOf(1)
        }

        assertEquals(1, code)
    }


    @Test fun `when killing a process should exit without finishing`() = runBlocking<Unit>{
        //setup
        val runningProcess = execAsync {
            command = promptScriptCommand()
        }

        //act
        val completedAfter100ms = withTimeoutOrNull(1000) { runningProcess.join() } != null
        if( ! runningProcess.isClosedForReceive){
            runningProcess.kill()
        }

        //assert
        assertFalse(completedAfter100ms)
        assertTrue(runningProcess.exitCode.isCompleted)
        assertFalse(runningProcess.exitCode.isActive)
        assertNotListed(runningProcess.processID)
    }

    @Test fun `when abandoning a process should hold parent scope open`() = runBlocking<Unit>{
        // interesting behaviour: in moving from coroutines 0.X to 1.0 (ie adding parent job scope)
        // I simply attached everything that this library did to the provided parent scope
        // the result was that abandoning hanging processes would hang the parent job.
        // At first this was a problem,
        // but thinking about it, its probably best that abandon processes keep something open in their parent
        // process since an abandon process is effectively a leaked process.

        // Thus, to avoid "leaking" processes, we get this behaviour:
        var actionSequence: List<String> = emptyList()
        val jobThatSpawnsSubProcess = launch {
            val proxy = this.execAsync {
                command = hangingCommand()
            }

            actionSequence += "started sub-process"

            // note: we never explicitly synchronize on `proxy.await()` or similar.
            // meaning this job will collapse but the job wont change to "finished"
        }

        delay(10)
        actionSequence += "sub-process status check: isComplete=${jobThatSpawnsSubProcess.isCompleted}"

        jobThatSpawnsSubProcess.cancel()

        assertEquals(listOf(
                "started sub-process",
                "sub-process status check: isComplete=false"
        ), actionSequence)
    }

    @Test fun `when cancelling the parent job of a sub-process kill child process`() = runBlocking<Unit>{

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
        assertThrows<CancellationException> { runningProcess.exitCode.await() }
        assertNotListed(runningProcess.processID)
    }

    @Test
    @Ignore("see https://github.com/Groostav/kotlinx.exec/issues/3")
    fun `when exiting normally should perform orderly shutdown`(): Unit = runBlocking {
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
        assertNotListed(process.processID)

        fail("this is a flapper, and we're going to need heavier-handed solutions to actually get a certain shutdown order.")
        // while I'm pretty sure the zipper is functioning correctly,
        // you have no gaurentee that a resume() call actually propagates forward in the order you want it to.
        // I'm not sure how we can get to deterministic shutdown... or if its even worth getting to...
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
        assertNotListed(runningProcess.processID)

        // I'd like this, but elizarov's own notes say its not a requirement
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
        assertEquals(StandardErrorMessage::class, aggregateChannelList.first()::class)
        assertEquals(ExitCode(1), aggregateChannelList.last())
        assertTrue("Script is exiting with code 1" in errorChannel.joinToString(""))
        assertEquals(listOf(), stdoutChannel)
        assertNotListed(runningProcess.processID)
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

    @Test fun `when synchronous exec sees bad exit code should throw good exception`() = runBlocking {

        val thrown = try {
            execVoid {
                command = errorAndExitCodeOneCommand()
                expectedOutputCodes = setOf(0) //make default explicity for clarity --exit code 1 => exception
            }
            null
        }
        catch(ex: InvalidExitValueException){ ex }

        assertEquals(
                //assert that the stack-trace points to exec.exec() at its top --not into the belly of some coroutine
                "groostav.kotlinx.exec.ExecKt.execVoid(exec.kt:LINE_NUM)",
                thrown?.stackTrace?.get(0)?.toString()?.replace(Regex(":\\d+\\)"), ":LINE_NUM)")
        )
    }

    @Test fun `when asynchronous exec sees bad exit code should throw ugly exception with good cause`() = runBlocking {

        val thrown = try {
            execAsync {
                command = errorAndExitCodeOneCommand()
                expectedOutputCodes = setOf(0) //make default explicity for clarity --exit code 1 => exception
            }.exitCode.await()
            null
        }
        catch (ex: InvalidExitValueException) { ex }
        val firstStackFrame = thrown?.stackTrace?.get(0)?.toString() ?: ""
        assertTrue("stack frame: $firstStackFrame points inside kotlinx.exec") {
            //assert that this stack exists, but it points somewhere inside a coroutine,
            firstStackFrame.startsWith("groostav.kotlinx.exec")
        }
        assertNotNull(thrown?.cause)
        assertEquals(
                //assert that the stack-trace points to exec.exec() at its top --not into the belly of some coroutine
                "groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:LINE_NUM)",
                thrown?.cause?.stackTrace?.get(0)?.toString()?.replace(Regex(":\\d+\\)"), ":LINE_NUM)")
        )
    }

    @Test fun `when killing process tree should properly end all descendants`() = runBlocking<Unit> {

        //setup
        val pidRegex = Pattern.compile("PID=(?<pid>\\d+)")

        val runningProcess = execAsync {
            command = forkerCommand()
            includeDescendantsInKill = true
        }

        val pids = runningProcess
                .map { it.also { trace { it.formattedMessage }}}
                .map { pidRegex.matcher(it.formattedMessage) }
                .filter { it.find() }
                .map { it.group("pid")?.toInt() ?: TODO() }
                .take(3)
                .toList()

        //act
        runningProcess.kill()

        //assert
        pids.forEach { assertNotListed(it) }

//        fail("this test passes on linux when I dont include the kill-child implementation, so my oracle's broken :sigh:")
        // blah, running forker-compose-up.sh from command line, then ctrl + Z, then ps, then kill -9 (parent), then ps
        // notice that all the child processes are dead. clearly I dont know enough about parent-child process relationships.
        // it seems that kill -9 in this cercomstance is giving me the "end process tree" behaviour I wanted. 
    }

    @Test fun `when calling kill forcefully should X`() = runBlocking<Unit>{

        //setup
        val process = execAsync {
            command = hangingCommand()
        }

        //act
        process.kill()

        //assert

    }

    @Test fun `when attempting to write to stdin after process sterminates should X`() = runBlocking<Unit> {
        //setup
        val process = execAsync {
            command = emptyScriptCommand()
        }
        process.exitCode.await()

        //act & assert
        assertThrows<CancellationException> {
            process.send("posthumously pestering")
        }
    }
}


