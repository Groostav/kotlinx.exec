package groostav.kotlinx.exec

import assertThrows
import getLocalResourcePath
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.selects.select
import org.amshove.kluent.*
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import queueOf
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

// Wow, so powershell takes 10X longer to start (~1 second) than cmd (~100ms)
// I suppose thats .netframework startup time, which is supposidly faster than the jvm, but it sure ain't fast.
class WindowsTests {

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
                StandardOutputMessage("hello"),
                StandardOutputMessage("nextline!"),
                ExitCode(0)
        )
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

    @Test fun `when running standard error chatty script with bad exit code should get the tail of that error output`() = runBlocking<Unit> {
        val chattyScript = getLocalResourcePath("ChattyErrorScript.ps1")

        val thrown = try {
            val running = execAsync {
                command = listOf("powershell.exe",
                        "-File", chattyScript,
                        "-ThrowError",
                        "-ExecutionPolicy", "Bypass"
                )
                linesForExceptionError = 5
            }
            running.exitCode.await()
            null
        }
        catch(ex: InvalidExitValueException){ ex }

        // assert that the error message contains the most recently emitted std-error message,
        // not something from the beginning
        val lines = (thrown?.message ?: "").lines().joinToString("") //do this to avoid powershell formatting
        assertFalse("The Polito form is dead, insect." in lines)
        assertTrue("I survived only by sleeping." in lines)
    }

    @Test fun `when command returns allowed nonzero exit code should return normally`() = runBlocking<Unit>{
        val simpleScript = getLocalResourcePath("SimpleScript.ps1")
        val (lines, _) = exec {
            command = listOf(
                    "powershell.exe",
                    "-File", simpleScript,
                    "-ExitCode", "1",
                    "-ExecutionPolicy", "Bypass"
            )
            expectedOutputCodes = setOf(1)
        }

        assertEquals(listOf<String>("env:GROOSTAV_ENV_VALUE is ''"), lines)
    }

    @Test fun `when using dropping buffer should not attempt to cache any output`() = runBlocking<Unit>{

        //setup
        val simpleScript = getLocalResourcePath("MultilineScript.ps1")

        //act
        val (output, code) = exec {
            aggregateOutputBufferLineCount = 1
            command = listOf(
                    "powershell.exe",
                    "-File", simpleScript,
                    "-ExecutionPolicy", "Bypass"
            )
        }

        //assert
        assertEquals(listOf<String>("nextline!"), output)
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

    @Test fun `when syncing on exit code output should still be available`() = runBlocking<Unit> {
        val simpleScript = getLocalResourcePath("SimpleScript.ps1")
        val runningProcess = execAsync {
            command = listOf(
                    "powershell.exe",
                    "-File", simpleScript,
                    "-ExecutionPolicy", "Bypass"
            )
        }
        val exitCode = runningProcess.exitCode.await()
        val lines = runningProcess.toList()

        assertEquals(listOf<ProcessEvent>(
                StandardOutputMessage("env:GROOSTAV_ENV_VALUE is ''"),
                ExitCode(0)
        ), lines)

    }

    @Test fun `when using raw character output should get sensable characters`() = runBlocking<Unit>{
        val script = getLocalResourcePath("MultilineScript.ps1")

        val runningProc = execAsync(
                "powershell.exe",
                "-ExecutionPolicy", "Bypass",
                "-File", script,
                "-Message", "testing!"
        )
        val chars = runningProc.standardOutput.toList()

        assertEquals(listOf<Char>('h', 'e', 'l', 'l', 'o', '\n', 'n', 'e', 'x', 't', 'l', 'i', 'n', 'e', '!', '\n'), chars)
    }

    @Test fun `using Read-Host with Prompt style powershell script should block script`() = runBlocking<Unit> {

        // this is realy unfortunate, and is a negative test.
        // Powershells concept of 'pipelines' and interactive scripts means that
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

    @Test fun `using inputPipeline style powershell script should rung normally`() = runBlocking<Unit> {

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

