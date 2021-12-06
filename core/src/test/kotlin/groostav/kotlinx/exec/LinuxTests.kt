package groostav.kotlinx.exec

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals

class LinuxTests {

    companion object {
        @JvmStatic @BeforeClass
        fun `should be windows`(){
            Assume.assumeTrue("assume $JavaProcessOS == Unix", JavaProcessOS == ProcessOS.Unix)
        }
    }

    @Ignore("functional")
    @Test fun `when running nautilus should get a nautilus`() = runBlocking<Unit> {
        val (output, code) = exec("nautilus")
        //interestingly, nautilus takes a long time to return.
        // That's not a bug, its just its behaviour.
        assertEquals(0, code)
    }


    @Test fun `when running simple echo statement should properly redirect`() = runBlocking<Unit> {

        val runningProcess = execAsync(commandLine=listOf("bash", "-c", "echo hello command line!"))

        val messages = runningProcess.map { event -> when(event){
            is StandardErrorMessage -> "std-err: ${event.line}"
            is StandardOutputMessage -> event.line
            is ExitCode -> "exit code: ${event.code}"
        }}

        val exitCode = runningProcess.await()
        val messagesList = messages.toList()

        assertEquals(listOf("hello command line!", "exit code: 0"), messagesList)
        assertEquals(0, exitCode)
    }


    @Test fun `when using script with simple slash-r style progress bar should render lien by line`() = runBlocking<Unit>{
        //setup
        val testScript = getLocalResourcePath("InlineProgressBar.sh")

        //act
        val (output, code) = exec("bash", testScript, "10")

        //assert
        assertEquals(listOf(
                "started Important task 42",
                "▇         | 10%",
                "▇▇        | 20%",
                "▇▇▇       | 30%",
                "▇▇▇▇      | 40%",
                "▇▇▇▇▇     | 50%",
                "▇▇▇▇▇▇    | 60%",
                "▇▇▇▇▇▇▇   | 70%",
                "▇▇▇▇▇▇▇▇  | 80%",
                "▇▇▇▇▇▇▇▇▇ | 90%",
                "▇▇▇▇▇▇▇▇▇▇| 100%",
                "", //this blank line comes simply from the way the progress bar is written
                "finished Important task 42"
        ), output)

    }

    @Test fun `when using standard linux command line idioms for progress bars should do something reasonable`(){
        TODO()
    }



}