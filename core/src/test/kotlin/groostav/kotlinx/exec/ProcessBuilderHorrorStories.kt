package groostav.kotlinx.exec

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.TimeoutException
import kotlin.test.assertNull

//this class is dedicated to prooving problems with the java process builder API.
class ProcessBuilderHorrorStories {

    private var resources: List<Process> = emptyList()

    @After fun cleanupProcesses() {
        resources.forEach { it.destroyForcibly() }
        resources = emptyList()
    }

    private operator fun Process.unaryPlus() = this.also { resources += this }

    @Test(timeout = 10_000)
    fun `when running the process builder if the output stream is not read from the process hangs`() = runBlocking<Unit> {
        val pb = ProcessBuilder().apply {
            command(iliadCommand())
        }

        //act
        val proc = +pb.start()

        //act
        val result = withTimeoutOrNull(5_000) {
            GlobalScope.launch(Dispatchers.IO) { proc.waitFor() }.join()
        }

        //assert
        assertNull(result) //demonstrates that waitFor never returned, even though the program is very simple,
        // it is blocked when trying to send the (bufferSize+1)th character to stdout.
    }

    @Test(timeout = 10_000)
    fun `when running a process that forks attaching to parent console and exits the output stream will not close`() = runBlocking<Unit>{

        // TODO see what behaviour we get on linux, this has got to be a windows specific behaviour.

        //setup
        val pb = ProcessBuilder().apply {
            command(singleParentSingleChildCommand())
        }
        val proc = +pb.start()
        withContext(Dispatchers.IO) { proc.waitFor() }

        // act
        val lines = withTimeoutOrNull(5_000) {
            GlobalScope.async(Dispatchers.IO) { proc.stdout.bufferedReader().readLines() }.await()
        }

        // assert

        assertNull(lines) //this demonstrates that the above job never returned (IE reader.readLines() hung for 5 seconds)
        // thus, while `waitFor` turned, `readLines()` did not. Pretty weird!! Java-Windows thinks zombies can talk!
    }
}

val Process.stdout: InputStream get() = inputStream