package groostav.kotlinx.exec

import getLocalResourcePath
import kotlinx.coroutines.experimental.channels.map
import kotlinx.coroutines.experimental.channels.toList
import kotlinx.coroutines.experimental.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldEqual
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
        val code = execVoid("nautilus")
        //interestingly, nautilus takes a long time to return.
        // That's not a bug, its just its behaviour.
        code shouldEqual 0
    }


    @Test fun `when running simple echo statement should properly redirect`() = runBlocking<Unit> {

        val runningProcess = execAsync {
            command = listOf("bash", "-c", "echo hello command line!")
        }

        val messages = runningProcess.map { event -> when(event){
            is StandardErrorMessage -> "std-err: ${event.line}"
            is StandardOutputMessage -> event.line
            is ExitCode -> "exit code: ${event.code}"
        }}

        val exitCode = runningProcess.exitCode.await()
        val messagesList = messages.toList()

        messagesList.shouldEqual(listOf(
                "hello command line!",
                "exit code: 0"
        ))
        exitCode.shouldBe(0)
    }


    @Test fun `when using script with simple slash-r style progress bar should render lien by line`() = runBlocking<Unit>{
        //setup
        val testScript = getLocalResourcePath("InlineProgressBar.sh")

        //act
        val proc = execAsync("bash", testScript, "10")
        val output = proc.standardOutput.lines().map { println(it); it }.toList()

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
                "",
                "finished Important task 42"
        ), output)

    }

    @Test fun `when using standard linux command line idioms for progress bars should do something reasonable`(){
        TODO()
    }



}