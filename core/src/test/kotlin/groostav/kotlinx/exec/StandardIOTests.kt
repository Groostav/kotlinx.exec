package groostav.kotlinx.exec

import com.sun.jna.Platform
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.core.Is
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals

class StandardIOTests {

    @Test
    fun `when running multiline script should get both lines`() = runBlocking<Unit>{
        //act
        val proc = execAsync(commandLine = printMultipleLinesCommand())
        val result = proc.toList()

        //assert
        assertEquals(listOf(
            StandardOutputMessage("hello"),
            StandardOutputMessage("nextline!"),
            ExitCode(0)
        ), result)
    }

    @Test fun `when running standard error chatty script with bad exit code should get the tail of that error output`() = runBlocking<Unit> {

        val thrown = try {
            val running = execAsync(
                commandLine = chattyErrorScriptCommand(),
                linesForExceptionError = 5
            )
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
        val (output, _) = exec(
            aggregateOutputBufferLineCount = 2,
            commandLine = printMultipleLinesCommand()
        )

        //assert
        assertEquals(listOf<String>("nextline!", "Process finished with exit code 0"), output)
    }

//    @Test fun `when using raw character output should get sensable characters`() = runBlocking<Unit>{
//
//        val runningProc = execAsync(
//            commandLine = printMultipleLinesCommand()
//        )
//        val chars = runningProc.standardOutput.toList()
//
//        assertEquals(listOf<Char>('h', 'e', 'l', 'l', 'o', '\n', 'n', 'e', 'x', 't', 'l', 'i', 'n', 'e', '!', '\n'), chars)
//    }

    @Test fun `when writing value to input stream should work as appropriate`() = runBlocking {

        //setup
        val runningProc = execAsync(
            commandLine = readToExitValue(),
            expectedOutputCodes = setOf(42)
        )

        //act
        runningProc.sendLine("42")
        val result = runningProc.await()

        //assert
        assertEquals(42, result)
    }


    @Test fun `when attempting to write to stdin after process terminates should throw CancellationException`() = runBlocking<Unit> {
        //setup
        val process = execAsync(commandLine = emptyScriptCommand())

        //act
        process.await()

        //assert
        assertThrows<IOException> {
            process.sendLine("posthumously pestering")
        } // looks like a regular closed Send channel.
    }

    @Test fun `assert that the polling or blocking thread for the IO is on stack trace when you do something like map on aggregate channel`() = runBlocking<Unit>{

        val runningProc = execAsync(commandLine = printMultipleLinesCommand())

        val exceptions = runningProc.map {
            Exception("stack producing: $it")
        }.toList()

        assertEquals(emptyList<Exception>(), exceptions)
    }

    @Test fun `when sending stdin before starting process should still operate correctly`() = runBlocking<Unit>{
        val proc = execAsync(
            commandLine = readToExitValue(),
            lazy = true,
            expectedOutputCodes = setOf(1234)
        )

        proc.sendLine("1234")

        proc.start()
        val result = proc.map { it.formattedMessage }.toList()
        assertEquals(listOf("1234", "result was 1234!", "Process finished with exit code 1234"), result)
        assertEquals(1234, proc.await())
    }
}