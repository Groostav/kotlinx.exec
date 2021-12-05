package groostav.kotlinx.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.core.IsEqual
import org.junit.Test
import java.lang.StringBuilder
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
class BadExitCodeTests {

    @Test
    fun `when async running process with unexpected exit code should throw good exception`() = runBlocking {
        //setup
        val runningProcess = execAsync(
            commandLine = errorAndExitCodeOneCommand()
        )

        //act
        val exitCodeResult = Catch<InvalidExitCodeException> { runningProcess.await() }    // throws exception
        val toListException = Catch<CancellationException> { runningProcess.toList() }  // also throws exception
        val joinResult = runningProcess.join()                                             // exits normally

        //assert
        assertEquals(Unit, joinResult)
        assertTrue(exitCodeResult is InvalidExitCodeException)
        assertTrue(toListException is CancellationException)
//        assertNotNull(exitCodeResult)
//        assertEquals(StandardErrorMessage::class, aggregateChannelList.first()::class)
//        assertEquals(ExitCode(1), aggregateChannelList.last())
//        assertTrue("Script is exiting with code 1" in aggregateChannelList.joinToString(""))
        assertNotListed(runningProcess.processID)
    }

    @Test
    fun `when synchronously running process with unexpected exit code should exit appropriately`() = runBlocking<Unit> {
        //setup & act
        val invalidExitValue = Catch<InvalidExitCodeException> {
            exec(commandLine = errorAndExitCodeOneCommand())
        }

        //assert
        assertNotNull(invalidExitValue)
        assertEquals(errorAndExitCodeOneCommand(), invalidExitValue.command)
        assertEquals(1, invalidExitValue.exitCode)
        assertThat(invalidExitValue.stackTraceWithoutLineNumbersToString(28).trim(), IsEqual.equalTo("""
                    groostav.kotlinx.exec.InvalidExitCodeException: exit code 1 from powershell.exe -ExecutionPolicy Bypass -File <SCRIPT_PATH>
                    the most recent standard-error output was:
                    <SCRIPT_PATH> : 
                    Script is exiting with code 1
                        + CategoryInfo          : NotSpecified: (:) [Write-Error], WriteErrorException
                        + FullyQualifiedErrorId : Microsoft.PowerShell.Commands.WriteErrorException,ExitCodeOne.ps1
                     
                    
                    ${TAB}at groostav.kotlinx.exec.CoroutineTracer.mark(CoroutineTracer.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.ExecCoroutine.await(ExecCoroutine.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.ExecKt.exec(exec.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.CoroutineTracer.ASYNC_RECOVERY_FOR_START(Unknown Source)
                    ${TAB}at groostav.kotlinx.exec.CoroutineTracer.mark(CoroutineTracer.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.ExecCoroutine.onStart(ExecCoroutine.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.ExecCoroutine.<init>(ExecCoroutine.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.ExecCoroutine.<init>(ExecCoroutine.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.ExecKt.exec(exec.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.ExecKt.exec${'$'}default(exec.kt:<LINE_NUM>)
                    ${TAB}at groostav.kotlinx.exec.BadExitCodeTests${'$'}when synchronously running process with unexpected exit code should exit appropriately${'$'}1.invokeSuspend(BadExitCodeTests.kt:<LINE_NUM>)
                    ${TAB}at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:<LINE_NUM>)
                    ${TAB}at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:<LINE_NUM>)
                    ${TAB}at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:<LINE_NUM>)
                    ${TAB}at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:<LINE_NUM>)
                    ${TAB}at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:<LINE_NUM>)
                    ${TAB}at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                    ${TAB}at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking${'$'}default(Builders.kt:<LINE_NUM>)
                    ${TAB}at kotlinx.coroutines.BuildersKt.runBlocking${'$'}default(Unknown Source)
                    ${TAB}at groostav.kotlinx.exec.BadExitCodeTests.when synchronously running process with unexpected exit code should exit appropriately(BadExitCodeTests.kt:<LINE_NUM>)
            """.trimIndent()
        ))
    }

    @Test
    fun `when asynchronously running process with unexpected exit code should exit appropriately`() = runBlocking<Unit> {
        //setup & act
        val expectedEx = Exception().stackTrace.drop(1)
        val invalidExitValue = Catch<InvalidExitCodeException> {
            execAsync(commandLine = errorAndExitCodeOneCommand()).await()
        }

        //assert
        assertNotNull(invalidExitValue)
        assertEquals(errorAndExitCodeOneCommand(), invalidExitValue.command)
        assertEquals(1, invalidExitValue.exitCode)
        assertEquals(
                expectedEx,
                invalidExitValue.stackTrace?.takeLast(expectedEx.size) ?: emptyList(),
                """Expected the exception
                   |${invalidExitValue.stackTrace.joinToString("\n\tat ")}
                   |to end with
                   |${expectedEx.joinToString("\n\tat ")}
                """.trimMargin())
    }

    @Test
    fun `when asynchronous exec sees bad exit code should throw ugly exception with good cause`() = runBlocking<Unit> {

        val thrown = try {
            execAsync(
                commandLine = errorAndExitCodeOneCommand(),
                expectedOutputCodes = setOf(0) //make default explicity for clarity --exit code 1 => exception
            ).await()
            null
        }
        catch (ex: InvalidExitCodeException) { ex }
        val firstStackFrame = thrown?.stackTrace?.get(0)?.toString() ?: ""

        assertThat(thrown?.stackTraceWithoutLineNumbersToString(20)?.trim() ?: "<null>", IsEqual.equalTo("""
                groostav.kotlinx.exec.InvalidExitCodeException: exit code 1 from powershell.exe -ExecutionPolicy Bypass -File <SCRIPT_PATH>
                the most recent standard-error output was:
                <SCRIPT_PATH> : 
                Script is exiting with code 1
                    + CategoryInfo          : NotSpecified: (:) [Write-Error], WriteErrorException
                    + FullyQualifiedErrorId : Microsoft.PowerShell.Commands.WriteErrorException,ExitCodeOne.ps1
                 
                
                ${TAB}at groostav.kotlinx.exec.CoroutineTracer.mark(CoroutineTracer.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.ExecCoroutine.await(ExecCoroutine.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.BadExitCodeTests${'$'}when asynchronous exec sees bad exit code should throw ugly exception with good cause${'$'}1.invokeSuspend(BadExitCodeTests.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.CoroutineTracer.ASYNC_RECOVERY_FOR_START(Unknown Source)
                ${TAB}at groostav.kotlinx.exec.CoroutineTracer.mark(CoroutineTracer.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.ExecCoroutine.onStart(ExecCoroutine.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.ExecCoroutine.<init>(ExecCoroutine.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.ExecCoroutine.<init>(ExecCoroutine.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.ExecKt.execAsync${'$'}default(exec.kt:<LINE_NUM>)
                ${TAB}at groostav.kotlinx.exec.BadExitCodeTests${'$'}when asynchronous exec sees bad exit code should throw ugly exception with good cause${'$'}1.invokeSuspend(BadExitCodeTests.kt:<LINE_NUM>)
            """.trimIndent()
        ))
    }
}

