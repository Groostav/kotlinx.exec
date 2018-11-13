package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
import org.amshove.kluent.*
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

// Wow, so powershell takes 10X longer to start (~1 second) than cmd (~100ms)
// I suppose thats .netframework startup time, which is supposidly faster than the jvm, but it sure ain't fast.
class WindowsTests {

    companion object {
        @JvmStatic @BeforeClass fun `should be windows`(){
            Assume.assumeTrue("assume $JavaProcessOS == Windows", JavaProcessOS == ProcessOS.Windows)
        }
    }

    @Ignore("functional")
    @Test fun `when running calc should get a calc`() = runBlocking<Unit> {
        val code = execVoid("calc.exe")

        code shouldEqual 0
    }

    @Test fun `when running simple echo statement should properly redirect`() = runBlocking<Unit> {

        val runningProcess = execAsync {
            command = listOf("cmd", "/C", "echo", "hello command line!")
        }

        val messages = runningProcess.map { event -> when(event){
            is StandardErrorMessage -> "std-err: ${event.line}"
            is StandardOutputMessage -> event.line
            is ExitCode -> "exit code: ${event.code}"
        }}

        val exitCode = runningProcess.exitCode.await()
        val messagesList = messages.toList()

        messagesList.shouldEqual(listOf(
                "\"hello command line!\"", //extra quotes inserted by cmd
                "exit code: 0"
        ))
        exitCode.shouldEqual(0)
    }


    val IP_REGEX = Regex("(\\d+\\.){3}\\d+")

    @Test fun channels_should_make_the_shell_interactive() = runBlocking<Unit>{

        //setup
        val testScript = getLocalResourcePath("InteractiveScript.ps1")

        //I'm going to create an actor as a Map<QuestionString, ResponseString>,
        // where "question" is the line output by the sub-process.
        class Message(val outputQuestionLine: String, val response: CompletableDeferred<String?> = CompletableDeferred())

        val localDecoder = actor<Message> {

            val domainsToTry: Queue<String> = queueOf("jetbrains.com", "asdf.asdf.asdf.12345.!!!")

            consumeEach { nextInput ->
                when(nextInput.outputQuestionLine) {
                    "Input the user name" -> nextInput.response.complete("groostav")
                    "Input your server name, or 'quit' to exit" -> {
                        val next = domainsToTry.poll() ?: "quit"
                        nextInput.response.complete(next)
                    }
                    else -> {
                        nextInput.response.complete(null)
                    }
                }
            }
        }

        //act
        val process = execAsync("powershell.exe", "-File", testScript, "-ExecutionPolicy", "Bypass")
        val runningOutputChannel = process.map { event -> when(event){
            is StandardErrorMessage -> event
            is StandardOutputMessage -> event.also {
                val message = Message(event.line)
                localDecoder.send(message)
                val nextInputLine = message.response.await()
                if(nextInputLine != null) { process.send(nextInputLine) }
            }
            is ExitCode -> event
        }}
        val result = runningOutputChannel
                .map { (it as? StandardOutputMessage)?.run { copy(line = line.replace(IP_REGEX, "1.2.3.4"))} ?: it }
                .map { it.also { trace { it.toString() } } }
                .toList()

        //assert
        val expectedAlmostSequence = listOf(
                StandardOutputMessage(line = "Serious script(tm)(C)(R)"),
                StandardOutputMessage(line = "Input the user name"),
                StandardOutputMessage(line = "groostav"),
                StandardOutputMessage(line = "Input your server name, or 'quit' to exit"),
                StandardOutputMessage(line = "jetbrains.com"),
                StandardOutputMessage(line = "Sorry groostav, jetbrains.com is already at 1.2.3.4"),
                StandardOutputMessage(line = "Input your server name, or 'quit' to exit"),
                StandardOutputMessage(line = "asdf.asdf.asdf.12345.!!!"),

                StandardErrorMessage(line = "Resolve-DnsName : asdf.asdf.asdf.12345.!!! : DNS name contains an invalid character"),
                StandardErrorMessage(line = "At $testScript:12 char:22"),
                StandardErrorMessage(line = "+     \$ServerDetails = Resolve-DnsName \$Server"),
                StandardErrorMessage(line = "+                      ~~~~~~~~~~~~~~~~~~~~~~~"),
                StandardErrorMessage(line = "    + CategoryInfo          : ResourceUnavailable: (asdf.asdf.asdf.12345.!!!:String) [Resolve-DnsName], Win32Exception"),
                StandardErrorMessage(line = "    + FullyQualifiedErrorId : DNS_ERROR_INVALID_NAME_CHAR,Microsoft.DnsClient.Commands.ResolveDnsName"),
                StandardErrorMessage(line = " "),

                StandardOutputMessage(line = "you're in luck groostav, asdf.asdf.asdf.12345.!!! isnt taken!"),
                StandardOutputMessage(line = "Input your server name, or 'quit' to exit"),
                StandardOutputMessage(line = "quit"),
                StandardOutputMessage(line = "Have a nice day!"),
                ExitCode(code = 0)
        )

        localDecoder.close()

        // regarding order: there is a race condition here
        // individual lines have happens-before, but the mixing of standard-error and standard-out isn't fixed here,
        // so to avoid flappers, we'll do set equality on the whole thing,
        // then sequence equality on the channels separately.
        // assertEquals(expectedAlmostSequence, result) passes _most_ of the time.
        assertEquals(expectedAlmostSequence.toSet(), result.toSet())
        assertEquals(expectedAlmostSequence.filterIsInstance<StandardOutputMessage>(), result.filterIsInstance<StandardOutputMessage>())
        assertEquals(expectedAlmostSequence.filterIsInstance<StandardErrorMessage>(), result.filterIsInstance<StandardErrorMessage>())
        assertEquals(expectedAlmostSequence.last(), result.last())
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

    @Test fun `when command returns non-zero exit code should throw by default`() = runBlocking<Unit>{

        val simpleScript = getLocalResourcePath("SimpleScript.ps1")

        val thrown = try {
            exec("powershell.exe",
                    "-File", simpleScript,
                    "-ThrowError",
                    "-ExecutionPolicy", "Bypass"
            )
            null
        }
        //note, a scripts 'thrown error' is simply 'exit code 1' in powershell semantics
        catch(ex: InvalidExitValueException){ ex }

        assertEquals("""
            |exec 'powershell.exe -File $simpleScript -ThrowError -ExecutionPolicy Bypass'
            |exited with code 1 (expected '0')
            |the most recent standard-error output was:
            |this is an important message!
            |At $simpleScript:12 char:5
            |+     throw "this is an important message!"
            |+     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            |    + CategoryInfo          : OperationStopped: (this is an important message!:String) [], RuntimeException
            |    + FullyQualifiedErrorId : this is an important message!
            |${" "}
            |
        """.trimMargin().lines(), thrown?.message?.lines())
    }

    @Test fun `when async command returns non-zero exit code should throw by default`() = runBlocking<Unit>{

        val simpleScript = getLocalResourcePath("SimpleScript.ps1")

        val thrown = try {
            val running = execAsync("powershell.exe",
                    "-File", simpleScript,
                    "-ThrowError",
                    "-ExecutionPolicy", "Bypass"
            )
            running.exitCode.await()
            null
        }
        catch(ex: InvalidExitValueException){ ex }

        assertEquals("""
            |exec 'powershell.exe -File $simpleScript -ThrowError -ExecutionPolicy Bypass'
            |exited with code 1 (expected '0')
            |the most recent standard-error output was:
            |this is an important message!
            |At $simpleScript:12 char:5
            |+     throw "this is an important message!"
            |+     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            |    + CategoryInfo          : OperationStopped: (this is an important message!:String) [], RuntimeException
            |    + FullyQualifiedErrorId : this is an important message!
            |${" "}
            |
        """.trimMargin().lines(), thrown?.message?.lines())
        assertNotNull(thrown?.cause)
        assertEquals(
                //assert that cause points to exec.exec() at its top --not into the belly of some coroutine
                "groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:LINE_NUM)",
                thrown?.cause?.stackTrace?.get(0)?.toString()?.replace(Regex(":\\d+\\)"), ":LINE_NUM)")
        )
    }

    @Test fun `when running with non standard env should do things`() = runBlocking<Unit> {
        val simpleScript = getLocalResourcePath("SimpleScript.ps1")
        val (lines, _) = exec {
            command = listOf(
                    "powershell.exe",
                    "-File", simpleScript,
                    "-ExecutionPolicy", "Bypass"
            )
            environment += "GROOSTAV_ENV_VALUE" to "Testing!"
        }

        assertEquals(listOf<String>(
                "env:GROOSTAV_ENV_VALUE is 'Testing!'"
        ), lines)

    }


    @Test fun `using Read-Host with Prompt style powershell script should block script`() = runBlocking<Unit> {

        // this is unfortunate, and is a negative test.
        // Powershell's concept of 'pipelines' and interactive scripts means that
        // Read-Host -Prompt "input question" will write _around_ standard-in/standard-out 'pipeline' concepts,
        // directly mutating the parent console window,
        // or in our case a chunk of memory we cant access from java-standard APIs.
        // So this test verifies that using a script with Read-Host -Prompt **does not** work.

        //setup
        val runningProc = execAsync {
            command = listOf(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-File", getLocalResourcePath("ReadHostStyleScript.ps1")
            )
            delimiters += ":" //doesnt help, the characters dont show up on the reader!
        }

        //act
        var result = emptyList<String>()
        while( ! runningProc.isClosedForReceive) {
            val next = select<String> {
                runningProc.onReceive { it -> it.formattedMessage }
                runningProc.exitCode.onAwait { it -> "exited" }

                if("hello!" in result) {
                    onTimeout(200) { "timed-out" }
                }
            }
            result += next
            if(next == "exited" || next == "timed-out") break;
        }

        //assert
        assertEquals(listOf("hello!", "timed-out"), result)
    }

    @Test fun `using inputPipeline style powershell script should run normally`() = runBlocking<Unit> {

        // in opposition to the above, powershell does 'wire standard-input' input to the 'input-pipeline',
        // so we can leverage that here fairly effectively.

        //setup
        val runningProc = execAsync {
            command = listOf(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-File", getLocalResourcePath("ReadPipelineInputStyleScript.ps1")
            )
        }
        val responses = queueOf("hello", "powershell!")

        //act
        var result = emptyList<String>()
        while( ! runningProc.isClosedForReceive) {
            val next = select<String> {
                runningProc.onReceive { it.formattedMessage }
                runningProc.exitCode.onAwait { "exited" }
            }
            result += next

            println("output=$next")

            val response: String? = when(next){
                "Go ahead and write things to input..." -> responses.poll() ?: "[EOF]"
                "exited" -> null
                else -> null
            }
            println("response=$response")

            when(response){
                "[EOF]" -> runningProc.close() //you may also write runningProc.inputstream.close()
                is String -> runningProc.send(response)
                null -> {}
            }
            if(next == "exited" || next == "timed-out") break;
        }

        //assert
        assertEquals(
                listOf(
                        "Go ahead and write things to input...",
                        "processing hello!",
                        "Go ahead and write things to input...",
                        "processing powershell!!",
                        "Go ahead and write things to input...", //send EOF signal
                        "done!",
                        "exited"
                ),
        result)
    }
}

