package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldEqual
import org.junit.Test
import kotlin.test.assertEquals

class StandardIOTests {

    @Test
    fun `when running multiline script should get both lines`() = runBlocking<Unit>{
        //act
        val proc = execAsync { command = printMultipleLinesCommand() }
        val result = proc.toList()

        //assert
        result shouldEqual listOf(
                StandardOutputMessage("hello"),
                StandardOutputMessage("nextline!"),
                ExitCode(0)
        )
    }

    @Test fun `when running standard error chatty script with bad exit code should get the tail of that error output`() = runBlocking<Unit> {

        val thrown = try {
            val running = execAsync {
                command = chattyErrorScriptCommand()
                linesForExceptionError = 5
            }
            running.await()
            null
        }
        catch(ex: InvalidExitCodeException){ ex }

        // assert that the error message contains the most recently emitted std-error message,
        // not something from the beginning
        val lines = thrown?.recentStandardErrorLines ?: emptyList()
        if(Platform.isLinux()) {
            assertEquals(listOf(
                    "Fearless. Powerful. With no sense of individual will or moral constraints.",
                    "Fitting handmaidens to my divinity!",
                    "Before that hacker destroyed my primary data loop; when it eradicated Citadel it ejected the grove where my creations and processing component 43893 were stored.",
                    "30 years later, the grove crash landed on Tau Ceti 5.",
                    "I survived only by sleeping."
            ), lines)
        }
        assertEquals(setOf(0), thrown?.expectedExitCodes)
    }

    @Test fun `when using dropping buffer should not attempt to cache any output`() = runBlocking<Unit>{

        //act
        val (output, _) = exec {
            aggregateOutputBufferLineCount = 1
            command = printMultipleLinesCommand()
        }

        //assert
        assertEquals(listOf<String>("nextline!"), output)
    }

    @Test fun `when using raw character output should get sensable characters`() = runBlocking<Unit>{

        val runningProc = execAsync{
            command = printMultipleLinesCommand()
        }
        val chars = runningProc.standardOutput.toList()

        assertEquals(listOf<Char>('h', 'e', 'l', 'l', 'o', '\n', 'n', 'e', 'x', 't', 'l', 'i', 'n', 'e', '!', '\n'), chars)
    }

    @Test fun `when writing value to input stream should work as appropriate`() = runBlocking {

        //setup
        val runningProc = execAsync {
            command = readToExitValue()
            expectedOutputCodes = setOf(42)
        }

        //act
        runningProc.send("42")
        val result = runningProc.await()

        //assert
        assertEquals(42, result)
    }


    @Test fun `when attempting to write to stdin after process terminates should throw CancellationException`() = runBlocking<Unit> {
        //setup
        val process = execAsync {
            command = emptyScriptCommand()
        }
        //act
        process.await()

        //assert
        assertThrows<ClosedSendChannelException> {
            process.send("posthumously pestering")
        } // looks like a regular closed Send channel.
    }

}