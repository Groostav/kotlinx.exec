package groostav.kotlinx.exec

import assertThrows
import emptyScriptCommand
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class UnhappyConfigurationTests {


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

    @Test fun todo(){
        TODO("""
            hmm, so aggregate channel acts as a null-object when set to 0 buffer,
            but stdout char channel explodes when you get() it with the same config, seems odd and inconsistent.
        """.trimIndent())
    }
}