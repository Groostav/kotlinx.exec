package groostav.kotlinx.exec

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.nio.file.Paths
import kotlin.test.assertEquals

@InternalCoroutinesApi
class UnhappyConfigurationTests {

    val OSLocalizedNoSuchFileMessage = when(JavaProcessOS) {
        ProcessOS.Windows -> "The system cannot find the file specified"
        ProcessOS.Unix -> "No such file or directory"
    }

    val OSLocalizedErrorPreamble = when(JavaProcessOS) {
        ProcessOS.Windows -> "CreateProcess "
        ProcessOS.Unix -> ""
    }

    @Test fun `when attempting to run nonexistant program should get exception`() = runBlocking<Unit> {

        //act
        val result = Catch<IOException> {
            exec(commandLine = listOf("prog-that-doesn't-exist-a1ccfa01-cf9a-474c-b95f-94377655ea75"))
        }

        //assert
        assertEquals("Cannot run program \"prog-that-doesn't-exist-a1ccfa01-cf9a-474c-b95f-94377655ea75\" " +
                "(in directory \"${Paths.get("").toAbsolutePath()}\"): " +
                "${OSLocalizedErrorPreamble}error=2, $OSLocalizedNoSuchFileMessage",
                result?.message
        )
    }

    @Test fun `when attempting to run empty command line should complain`() = runBlocking<Unit> {
        //act
        val result = Catch<IllegalArgumentException> {
            exec(commandLine = emptyList())
        }

        //assert
        assertEquals("cannot exec empty command", result?.message)
    }

    @Test fun `when attempting to read from unbufferred channel should get empty channel behaviour`() = runBlocking<Unit> {
        //setup
        val runningProcess = execAsync (
            commandLine = printMultipleLinesCommand(),
//            standardOutputBufferCharCount = 0,
//            standardErrorBufferCharCount = 0,
            aggregateOutputBufferLineCount = 0
        )

        runningProcess.await()

        //act & assert
//        assertTrue(runningProcess.standardError.isEmpty)
//        assertTrue(runningProcess.standardOutput.isEmpty)
        assertEquals(listOf(ExitCode(0)), runningProcess.toList())

        //cleanup --done outside of finally block because above errors are more important
        runningProcess.join()
    }

    @Test fun `when attempting to get status of unbufferred channel should get good behaviour`() = runBlocking<Unit> {

        //setup
        val runningProcess = execAsync(
            commandLine = emptyScriptCommand(),
            aggregateOutputBufferLineCount = 0
        )

        //act
        val beforeClose = object {
            val isCompleted: Boolean = runningProcess.isCompleted
            val poll: ChannelResult<ProcessEvent>? = runningProcess.tryReceive()
        }

        runningProcess.sendLine("done!")
        val receivedElement = runningProcess.receiveCatching().getOrNull()
        runningProcess.join()

        val afterClose = object {
            val isCompleted: Boolean = runningProcess.isCompleted
            val poll: ChannelResult<ProcessEvent>? = runningProcess.tryReceive()
        }

        //assert
        assertEquals(false, beforeClose.isCompleted)
        assertEquals(ChannelResult.failure<Nothing>(), beforeClose.poll)
        assertEquals(ExitCode(0), receivedElement)
        assertEquals(true, afterClose.isCompleted)
        assertEquals(ChannelResult.closed<Nothing>(null), afterClose.poll)
    }
}
