package groostav.kotlinx.exec

import Catch
import assertNotListed
import assertThrows
import completableScriptCommand
import emptyScriptCommand
import errorAndExitCodeOneCommand
import forkerCommand
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.filter
import kotlinx.coroutines.experimental.channels.map
import kotlinx.coroutines.experimental.channels.take
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

    @Test fun `when exiting normally should perform orderly shutdown`(): Unit = runBlocking {
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
        TODO("this is a flapper, and we're going to need heavier-handed solutions to actually get a certain shutdown order.")
        // while I'm pretty sure the zipper is functioning correctly,
        // you have no gaurentee that a resume() call actually propagates forward in the order you want it to.
        // I'm not sure how we can get to deterministic shutdown... or if its even worth getting to...
        assertNotListed(process.processID)
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
        assertTrue(
                //assert that this stack exists, but it points somewhere inside a coroutine,
                (thrown?.stackTrace?.get(0)?.toString() ?: "").startsWith("groostav.kotlinx.exec")
        )
        assertNotNull(thrown?.cause)
        assertEquals(
                //assert that the stack-trace points to exec.exec() at its top --not into the belly of some coroutine
                "groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:LINE_NUM)",
                thrown?.cause?.stackTrace?.get(0)?.toString()?.replace(Regex(":\\d+\\)"), ":LINE_NUM)")
        )
    }

    @Test fun `when killing process tree should properly end all descendants`() = runBlocking {

        //setup
        val pidRegex = Regex("PID=(?<pid>\\d+)")

        val runningProcess = execAsync {
            command = forkerCommand()
            includeDescendantsInKill = true
        }

        val pids = runningProcess
                .map { it.also { trace { it.formattedMessage }}}
                .filter { pidRegex.containsMatchIn(it.formattedMessage) }
                .map { pidRegex.find(it.formattedMessage)?.groups?.get("pid")?.value?.toInt() ?: TODO() }
                .take(3)
                .toList()

        //act
        runningProcess.kill()

        //assert
        pids.forEach { assertNotListed(it) }
    }

}


