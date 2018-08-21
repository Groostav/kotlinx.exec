package groostav.kotlinx.exec

import Catch
import assertThrows
import emptyScriptCommand
import kotlinx.coroutines.experimental.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import java.io.IOException
import java.nio.file.Paths
import java.util.*
import kotlin.test.assertEquals

class UnhappyConfigurationTests {


    @Test fun `when attempting to run nonexistant program should get exception`() = runBlocking<Unit> {

        //act
        val result = Catch<InvalidExecConfigurationException> {
            exec { command = listOf("prog-that-doesn't-exist-a1ccfa01-cf9a-474c-b95f-94377655ea75") }
        }

        //assert
        assertEquals("Cannot run program \"prog-that-doesn't-exist-a1ccfa01-cf9a-474c-b95f-94377655ea75\" " +
                "(in directory \"${Paths.get("").toAbsolutePath()}\"): " +
                "CreateProcess error=2, The system cannot find the file specified",
                result?.message
        )
    }

    @Test fun `when attempting to run empty command line should complain`() = runBlocking<Unit> {
        //act
        val result = Catch<InvalidExecConfigurationException> {
            exec { command = emptyList() }
        }

        //assert
        assertEquals("cannot exec empty command", result?.message)
    }

    @Test fun `when attempting to read from unbufferred channel should get exception`() = runBlocking<Unit> {
        //setup
        val runningProcess = execAsync {
            command = emptyScriptCommand()

            standardOutputBufferCharCount = 0
            standardErrorBufferCharCount = 0
            aggregateOutputBufferLineCount = 0
        }

        //act & assert
        assertThrows<IllegalStateException> { runningProcess.standardError }
        assertThrows<IllegalStateException> { runningProcess.standardError }

        //cleanup --done outside of finally block because above errors are more important
        runningProcess.join()
    }

    @Test fun `when attempting to get status of unbufferred channel should get good behaviour`() = runBlocking<Unit> {

        //setup
        val runningProcess = execAsync {
            command = emptyScriptCommand()
            aggregateOutputBufferLineCount = 0
        }

        //act
        val beforeClose = object {
            val isClosedForReceive = runningProcess.isClosedForReceive
            val isEmpty = runningProcess.isEmpty
            val poll = runningProcess.poll()
        }

        runningProcess.send("done!")
        val receivedElement = runningProcess.receiveOrNull()
        runningProcess.join()

        val afterClose = object {
            val isClosedForReceive = runningProcess.isClosedForReceive
            val isEmpty = runningProcess.isEmpty
            val poll = runningProcess.poll()
        }

        //act
        assertEquals(false, beforeClose.isClosedForReceive)
        assertEquals(true, beforeClose.isEmpty)
        assertEquals(null, beforeClose.poll)
        assertEquals(ExitCode(0), receivedElement)
        assertEquals(true, afterClose.isClosedForReceive)
        assertEquals(false, afterClose.isEmpty) //thats pretty weird, a closed channel is non-empty? huh.
        assertEquals(null, afterClose.poll)
    }
}
