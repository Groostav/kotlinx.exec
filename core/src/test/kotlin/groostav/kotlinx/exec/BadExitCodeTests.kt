package groostav.kotlinx.exec

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * This library throws `InvalidExitCodeException`s as a way to aid in debugging.
 *
 * The rationale is as follows:
 * 1. when a process emits a non-zero (or otherwise unexpected) error code it likely did not perform its function
 * 2. programmers may reasonably wish to presume invariance in its subprocess input domains
 *    eg you may write your code assuming internet access and call curl,
 *    making a thrown exception reasonable when your program is run without internet access
 * 3. bad process exit codes are often accompanied by useful stderr messages,
 *    those should then be "stapled" to the bad exit code by default
 * 4. users are not likely to attempt to enumerate all possible exit values, and the most suitable `default`
 *    would likely be `throw UnsupportedOperationException`
 *
 * Thus, this library asks you for a set of expected exit codes, runs your process,
 * and throws an exception with a copy of recent std-err when the actual exit code does not match.
 */
@InternalCoroutinesApi
class BadExitCodeTests {

    @Test
    fun `when async running process with unexpected exit code should throw good exception`() = runBlocking {
        //setup
        val runningProcess = execAsync {
            command = errorAndExitCodeOneCommand()
        }

        //act
        val joinResult = runningProcess.join()                                                           // exits normally
        val exitCodeResult = Catch<InvalidExitCodeException> { runningProcess.await() } // throws exception
        val aggregateChannelList = runningProcess.toList()                                    // produces list with exit code
        val errorChannel = runningProcess.standardError.toList()                                    // exits normally
        val stdoutChannel = runningProcess.standardOutput.toList()                                  // exits normally

        //assert
        assertEquals(Unit, joinResult)
        assertTrue(exitCodeResult is InvalidExitCodeException)
        assertNotNull(exitCodeResult)
        assertEquals(StandardErrorMessage::class, aggregateChannelList.first()::class)
        assertEquals(ExitCode(1), aggregateChannelList.last())
        assertTrue("Script is exiting with code 1" in errorChannel.joinToString(""))
        assertEquals(emptyList(), stdoutChannel)
        assertNotListed(runningProcess.processID)
    }

    @Test
    fun `when synchronously running process with unexpected exit code should exit appropriately`() = runBlocking<Unit> {
        //setup & act
        val expectedEx = Exception().stackTrace.drop(1)
        val invalidExitValue = Catch<InvalidExitCodeException> {
            execVoid {
                command = errorAndExitCodeOneCommand()
            }
        }

        //assert
        assertNotNull(invalidExitValue)
        assertEquals(errorAndExitCodeOneCommand(), invalidExitValue.command)
        assertEquals(1, invalidExitValue.exitValue)
        assertEquals(
                expectedEx,
                (invalidExitValue.cause as? SynchronousExecutionStart)?.stackTrace?.takeLast(expectedEx.size) ?: emptyList(),
                """Expected the exception
                   |${invalidExitValue.stackTrace.joinToString("\n\tat ")}
                   |to end with
                   |${expectedEx.joinToString("\n\tat ")}
                """.trimMargin())
    }

    @Test
    fun `when asynchronously running process with unexpected exit code should exit appropriately`() = runBlocking<Unit> {
        //setup & act
        val expectedEx = Exception().stackTrace.drop(1)
        val invalidExitValue = Catch<InvalidExitCodeException> {
            execAsync {
                command = errorAndExitCodeOneCommand()
            }.await()
        }

        //assert
        assertNotNull(invalidExitValue)
        assertEquals(errorAndExitCodeOneCommand(), invalidExitValue.command)
        assertEquals(1, invalidExitValue.exitValue)
        assertEquals(
                expectedEx,
                (invalidExitValue.cause as? AsynchronousExecutionStart)?.stackTrace?.takeLast(expectedEx.size) ?: emptyList(),
                """Expected the exception
                   |${invalidExitValue.stackTrace.joinToString("\n\tat ")}
                   |to end with
                   |${expectedEx.joinToString("\n\tat ")}
                """.trimMargin())
    }

    @Test
    fun `when asynchronous exec sees bad exit code should throw ugly exception with good cause`() = runBlocking {

        val thrown = try {
            execAsync {
                command = errorAndExitCodeOneCommand()
                expectedOutputCodes = setOf(0) //make default explicity for clarity --exit code 1 => exception
            }.await()
            null
        }
        catch (ex: InvalidExitCodeException) { ex }
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
}