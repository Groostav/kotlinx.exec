package groostav.kotlinx.exec

import junit.framework.Assert.assertEquals
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.amshove.kluent.*
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Paths
import java.util.*


class WindowsTests {

    @Ignore("functional")
    @Test fun `when running calc should get a calc`() = runBlocking<Unit> {
        val code = exec("calc.exe")

        code shouldEqual 0
    }

    @Test fun `when running simple echo statement should properly redirect`() = runBlocking<Unit> {

        val processSpec = ProcessBuilder().apply {
            outputHandlingStrategy = OutputHandlingStrategy.Buffer
            command = listOf("cmd", "/C", "echo", "hello command line!")
        }

        val runningProcess = execAsync(processSpec)

        val messages = runningProcess.map { event -> when(event){
            is StandardError -> DomainModel("Error: ${event.line}")
            is StandardOutput -> DomainModel(event.line)
            is ExitCode -> DomainModel("exit code: ${event.value}")
        }}

        val exitCode = runningProcess.exitCode.await()
        val messagesList = messages.toList()

        messagesList.shouldEqual(listOf(
                DomainModel("\"hello command line!\""), //extra quotes inserted by cmd
                DomainModel("exit code: 0")
        ))
        exitCode.shouldBe(0)
    }

    @Test fun `when running multiline script should get both lines`() = runBlocking<Unit>{
        //setup
        val resource = getLocalResourcePath("MultilineScript.ps1")

        //act
        val proc = execAsync(
                "powershell.exe",
                "-File", resource,
                "-ExecutionPolicy", "Bypass"
        )
        val result = proc.toList()

        //assert
        result shouldEqual listOf(
                StandardOutput("hello"),
                StandardOutput("nextline!"),
                ExitCode(0)
        )
    }

    @Ignore //really nasty test, how can we make these fast? JimFS for processes?
    @Test fun `while trying to recover a stuck command`() = runBlocking<Unit> {
        val process = execAsync(
                "powershell.exe",
                "-Command", "while(\$true) { echo \"running!\"; Sleep -m 500 }",
                "-ExecutionPolicy", "Bypass"
        )

        delay(5000)

        process.kill()

        val stdout = process.standardOutput.toList()
        val result = process.exitCode.await()

        result shouldEqual 1
        stdout shouldContain "running!"
        stdout.size shouldBeGreaterThan ((5000-1) / 500)
    }

    @Test fun channels_should_make_the_shell_interactive() = runBlocking<Unit>{

        //setup
        val testScript = getLocalResourcePath("InteractiveScript.ps1")

        //I'm going to create an actor as a Map<QuestionString, ResponseString>,
        // where "question" is the line output by the sub-process.
        class Message(val outputQuestionLine: String, val response: CompletableDeferred<String?> = CompletableDeferred())
        val localDecoder = actor<Message> {

            val domainsToTry: Queue<String> = queueOf("jetbrains.com", "asdf.asdf.asdf.12345.!!!")

            consumeEach { nextInput ->
                testing(nextInput)
                when(nextInput.outputQuestionLine) {
                    "Input the user name" -> nextInput.response.complete("groostav")
                    "Input your server name, or 'quit' to exit" -> {
                        val next = domainsToTry.poll() ?: "quit"
                        nextInput.response.complete(next)
                    }
                    else -> nextInput.response.complete(null)
                }
            }
        }

        //act
        val process = execAsync("powershell.exe", "-File", testScript, "-ExecutionPolicy", "Bypass")
        val runningOutputChannel = process.map { event -> when(event){
            is StandardError -> event
            is StandardOutput -> event.also {
                val message = Message(event.line)
                localDecoder.send(message)
                val nextInputLine = message.response.await()
                if(nextInputLine != null) { process.send(nextInputLine) }
            }
            is ExitCode -> event
        }}
        val result = runningOutputChannel.map { it.also { println(it) } }.toSet()

        //assert
        result shouldEqual setOf(
                StandardOutput(line="Serious script(tm)(C)(R)"),
                StandardOutput(line="Input the user name"),
                StandardOutput(line="groostav"),
                StandardOutput(line="Input your server name, or 'quit' to exit"),
                StandardOutput(line="jetbrains.com"),
                StandardOutput(line="Sorry groostav, jetbrains.com is already at 54.225.64.222"), //TODO: this IP changes... herp derp.
                StandardOutput(line="Input your server name, or 'quit' to exit"),
                StandardOutput(line="asdf.asdf.asdf.12345.!!!"),

                // regarding order: there is a race condition here
                // the next output line could come before this,
                // so to stave-off the flapper I'm doing set equality rather than list equality.
                StandardError(line="Resolve-DnsName : asdf.asdf.asdf.12345.!!! : DNS name contains an invalid character"),
                StandardError(line="At $testScript:12 char:22"),
                StandardError(line="+     \$ServerDetails = Resolve-DnsName \$Server"),
                StandardError(line="+                      ~~~~~~~~~~~~~~~~~~~~~~~"),
                StandardError(line="    + CategoryInfo          : ResourceUnavailable: (asdf.asdf.asdf.12345.!!!:String) [Resolve-DnsName], Win32Exception"),
                StandardError(line="    + FullyQualifiedErrorId : DNS_ERROR_INVALID_NAME_CHAR,Microsoft.DnsClient.Commands.ResolveDnsName"),
                StandardError(line=" "),

                StandardOutput(line="you're in luck groostav, asdf.asdf.asdf.12345.!!! isnt taken!"),
                StandardOutput(line="Input your server name, or 'quit' to exit"),
                StandardOutput(line="quit"),
                StandardOutput(line="Have a nice day!"),
                ExitCode(value=0)
        )
    }

    @Test fun `when using script with Write-Progress style progress bar should only see echo statements`() = runBlocking <Unit>{
        //setup
        val testScript = getLocalResourcePath("WriteProgressStyleProgressBar.ps1")

        //act
        val proc = execAsync("powershell.exe",
                "-File", testScript,
                "-SleepTime", "1",
                "-ExecutionPolicy", "Bypass"
        )
        val output = proc.standardOutput.lines().map { println(it); it }.toList()

        //assert
        output shouldEqual listOf(
                "Important task 42",
                //unfortunately the progress bar, which shows up as a nice UI element in powershell ISE
                // or as a set of 'ooooooo's in powershell terminal doesnt get emitted to any standard output channel, so we loose it.
                "done Important Task 42!"
        )

    }

    @Test fun `when using script with simple slash-r style progress bar should render lien by line`() = runBlocking<Unit>{
        //setup
        val testScript = getLocalResourcePath("WriteInlineProgressStyleProgressBar.ps1")

        //act
        val proc = execAsync("powershell.exe",
                "-File", testScript,
                "-SleepTime", "1",
                "-ExecutionPolicy", "Bypass"
        )
        val output = proc.standardOutput.lines().map { println(it); it }.toList()

        //assert
        assertEquals(listOf(
                "started Important task 42",
                "Important Task 42    [#####..............................................]  10% ",
                "Important Task 42    [##########.........................................]  20% ",
                "Important Task 42    [###############....................................]  30% ",
                "Important Task 42    [####################...............................]  40% ",
                "Important Task 42    [#########################..........................]  50% ",
                "Important Task 42    [##############################.....................]  60% ",
                "Important Task 42    [###################################................]  70% ",
                "Important Task 42    [########################################...........]  80% ",
                "Important Task 42    [#############################################......]  90% ",
                "Important Task 42    [###################################################] 100% ",
                "done Important Task 42!"
        ), output)

    }

    @Test fun `when looking for character streams should be relatively simple`(){
        TODO()
    }

    private fun getLocalResourcePath(localName: String): String {
        val rsx = WindowsTests::class.java.getResource(localName) ?: throw UnsupportedOperationException("cant find $localName")
        val resource = Paths.get(rsx.toURI()).toString()
        return resource
    }

}

internal fun testing(arg: Any? = null){
    val x = 4;
}

data class DomainModel(val data: String)

private inline fun <T> queueOf(vararg elements: T): Queue<T> {
    val result = LinkedList<T>()
    elements.forEach { result.add(it) }
    return result
}
