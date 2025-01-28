package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import org.hamcrest.core.IsEqual
import org.junit.Assert.assertThat
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

// Wow, so powershell takes 10X longer to start (~1 second) than cmd (~100ms)
// I suppose thats .netframework startup time, which is supposidly faster than the jvm, but it sure ain't fast.
class WindowsTests {

    companion object {
        @JvmStatic @BeforeClass fun `should be windows`(){
            Assume.assumeTrue("assume $JavaProcessOS == Windows", JavaProcessOS == ProcessOS.Windows)
        }

        init {
            System.setProperty("groostav.kotlinx.exec.trace", "true")
        }
    }

    @Ignore("functional")
    @Test fun `when running calc should get a calc`() = runBlocking<Unit> {
        val (output, code) = exec("calc.exe")

        assertEquals(0, code)
    }

    @Test fun `when running simple echo statement should properly redirect`() = runBlocking<Unit> {

        val runningProcess = execAsync(commandLine=listOf("cmd", "/C", "echo", "hello command line!"))

        val messages = runningProcess.map { event -> when(event){
            is StandardErrorMessage -> "std-err: ${event.line}"
            is StandardOutputMessage -> event.line
            is ExitCode -> "exit code: ${event.code}"
        }}

        val exitCode = runningProcess.await()
        val messagesList = messages.toList()

        assertEquals(listOf(
            "\"hello command line!\"", //extra quotes inserted by cmd
            "exit code: 0"
        ), messagesList)
        assertEquals(0, exitCode)
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
                if(nextInputLine != null) { process.sendLine(nextInputLine) }
            }
            is ExitCode -> event
        }}
        val result = select<List<ProcessEvent>?> {
            async {
                runningOutputChannel
                        .map {
                            (it as? StandardOutputMessage)?.run { copy(line = line.replace(IP_REGEX, "1.2.3.4")) } ?: it
                        }
                        .map { it.also { println(it.toString()) } }
                        .toList()
            }.onAwait { it }
            onTimeout(10_000_000) { null }
        }

        val x= 4;

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

        assertEquals(expectedAlmostSequence.filterIsInstance<StandardOutputMessage>(), result?.filterIsInstance<StandardOutputMessage>())
        assertEquals(expectedAlmostSequence.filterIsInstance<StandardErrorMessage>(), result?.filterIsInstance<StandardErrorMessage>())
        assertEquals(expectedAlmostSequence.last(), result?.last())
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
        val output = proc.mapNotNull { (it as? StandardOutputMessage)?.formattedMessage }.map { println(it); it }.toList()

        //assert
        assertEquals(listOf(
                "Important task 42",
                //unfortunately the progress bar, which shows up as a nice UI element in powershell ISE
                // or as a set of 'ooooooo's in powershell terminal doesnt get emitted to any standard output channel, so we loose it.
                "done Important Task 42!"
        ), output)

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
        val output = proc.mapNotNull { (it as? StandardOutputMessage)?.formattedMessage }.map { println(it); it }.toList()

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
        catch(ex: InvalidExitCodeException){ ex }

        assertThat(thrown?.message, IsEqual.equalTo("""
            |exit code 1 from powershell.exe -File $simpleScript -ThrowError -ExecutionPolicy Bypass
            |the most recent standard-error output was:
            |this is an important message!
            |At $simpleScript:12 char:5
            |+     throw "this is an important message!"
            |+     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            |    + CategoryInfo          : OperationStopped: (this is an important message!:String) [], RuntimeException
            |    + FullyQualifiedErrorId : this is an important message!
            |${" "}
            |
        """.trimMargin()))
    }

    @Test fun `when async command returns non-zero exit code should throw by default`() = runBlocking<Unit>{

        val simpleScript = getLocalResourcePath("SimpleScript.ps1")

        val thrown = try {
            val running = execAsync("powershell.exe",
                    "-File", simpleScript,
                    "-ThrowError",
                    "-ExecutionPolicy", "Bypass"
            )
            running.await()
            null
        }
        catch(ex: InvalidExitCodeException){ ex }

        assertThat(thrown?.stackTraceWithoutLineNumbersToString(22)?.trim(), IsEqual.equalTo("""
            groostav.kotlinx.exec.InvalidExitCodeException: exit code 1 from powershell.exe -File <SCRIPT_PATH> -ThrowError -ExecutionPolicy Bypass
            the most recent standard-error output was:
            this is an important message!
            At <SCRIPT_PATH>:12 char:5
            +     throw "this is an important message!"
            +     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                + CategoryInfo          : OperationStopped: (this is an important message!:String) [], RuntimeException
                + FullyQualifiedErrorId : this is an important message!
            ${" "}

            ${TAB}at groostav.kotlinx.exec.CoroutineTracer.mark(CoroutineTracer.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.ExecCoroutine.await(ExecCoroutine.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.WindowsTests${'$'}when async command returns non-zero exit code should throw by default${'$'}1.invokeSuspend(WindowsTests.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.CoroutineTracer.ASYNC_RECOVERY_FOR_START(Unknown Source)
            ${TAB}at groostav.kotlinx.exec.CoroutineTracer.mark(CoroutineTracer.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.ExecCoroutine.onStart(ExecCoroutine.kt:<LINE_NUM>)
            ${TAB}at kotlinx.coroutines.JobSupport.startInternal(JobSupport.kt:<LINE_NUM>)
            ${TAB}at kotlinx.coroutines.JobSupport.start(JobSupport.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.ExecKt.execAsync${'$'}default(exec.kt:<LINE_NUM>)
            ${TAB}at groostav.kotlinx.exec.WindowsTests${'$'}when async command returns non-zero exit code should throw by default${'$'}1.invokeSuspend(WindowsTests.kt:<LINE_NUM>)
        """.trimIndent()))

    }


    @Test fun `using Read-Host with Prompt style powershell script should block script`() = runBlocking<Unit> {

        // this is unfortunate, and is a negative test.
        // Powershell's concept of 'pipelines' and interactive scripts means that
        // Read-Host -Prompt "input question" will write _around_ standard-in/standard-out 'pipeline' concepts,
        // directly mutating the parent console window,
        // or in our case a chunk of memory we cant access from java-standard APIs.
        // So this test verifies that using a script with Read-Host -Prompt **does not** work.

        //setup
        val runningProc = execAsync(
            commandLine = listOf(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-File", getLocalResourcePath("ReadHostStyleScript.ps1")
            ),
            delimiters = listOf("\r", "\n", "\r\n", ":") //doesnt help, the characters dont show up on the reader!
        )

        //act
        var result = emptyList<String>()
        do {
            val next = withTimeoutOrNull(if("hello!" in result) 200 else 999_999) {
                runningProc.receiveCatching().getOrNull()?.formattedMessage ?: "closed"
            } ?: "timed-out"

            result += next
        }
        while(next != "exited" && next != "timed-out" && next != "closed")

        runningProc.kill(0)

        //assert
        assertEquals(listOf("hello!", "timed-out"), result)

//        fail; //bleh, so something is happening, some coroutine hasnt finished
        //oh, yeah, nothing here actually finishes it. lol. so does that mean the scope is a problem?
        //ok update: so somehow the kill command is having its error listener get attached after the process starts?
        //it seems that parentScope.launch(Unconfined) does not give you the behaviour of the ol' launch(Unconfined)
    }

    @Test fun `using inputPipeline style powershell script should run normally`() = runBlocking<Unit> {

        // in opposition to the above, powershell does 'wire standard-input' input to the 'input-pipeline',
        // so we can leverage that here fairly effectively.

        //setup
        val runningProc = execAsync(
            commandLine = listOf(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-File", getLocalResourcePath("ReadPipelineInputStyleScript.ps1")
            ),
            gracefulTimeoutMillis = 999999
        )
        val responses = queueOf("hello", "powershell!")

        //act
        var result = emptyList<String>()
        do {
            val next = select<String?> {
                runningProc.onAwait { "[exited]" }
                runningProc.onReceiveCatching { it.getOrNull()?.formattedMessage }
            }

            result += next ?: continue

            println("output=$next")

            val response: String? = when(next){
                "Go ahead and write things to input..." -> responses.poll() ?: "[EOF]"
                "[exited]" -> null
                else -> null
            }
            println("response=$response")

            when(response){
                "[EOF]" -> runningProc.kill(0)
                is String -> runningProc.sendLine(response)
                null -> {}
            }
        } while (next != "[exited]" && next != "timed-out")

        runningProc.join()

        //assert
        assertEquals(
                listOf(
                        "Go ahead and write things to input...",
                        "processing hello!",
                        "Go ahead and write things to input...",
                        "processing powershell!!",
                        "Go ahead and write things to input...", //send EOF signal
                        "done!",
                        "Process finished with exit code 0",
                        "[exited]"
                ),
                result
        )
    }
}

