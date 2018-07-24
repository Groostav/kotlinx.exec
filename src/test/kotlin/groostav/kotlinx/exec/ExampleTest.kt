package groostav.kotlinx.exec

import junit.framework.Assert.assertEquals
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.amshove.kluent.*
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Paths


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

        val wasCompleteAfterMessages = runningProcess.exitCode.isCompleted
        val exitCode = runningProcess.exitCode.await()
        val messagesList = messages.toList()

        //TODO: I dont know where these extra quotes are coming from..
        messagesList.shouldEqual(listOf(
                DomainModel("\"hello command line!\""),
                DomainModel("exit code: 0")
        ))
        exitCode.shouldBe(0)
        wasCompleteAfterMessages.shouldBeTrue()
    }

    @Test fun `when running multiline script should get both lines`() = runBlocking<Unit>{
        //setup
        val resource = getLocalResourcePath("MultilineScript.ps1")

        //act
        val proc = execAsync("powershell.exe", "-File", resource, "-ExecutionPolicy", "Bypass")
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
        val process = execAsync("powershell.exe", "-Command", "while(\$true) { echo \"running!\"; Sleep -m 500 }")

        delay(5000)

        process.kill()

        val stdout = process.standardOutput.toList()
        val result = process.exitCode.await()

        result shouldEqual 1
        stdout shouldContain "running!"
        stdout.size shouldBeGreaterThan ((5000-1) / 500)
    }

    @Test fun `channels should make the shell interactive`() = runBlocking<Unit>{

        val testScript = getLocalResourcePath("InteractiveScript.ps1")

        val process = execAsync("powershell.exe", "-File", testScript, "-ExecutionPolicy", "Bypass")

        val responses = produce<String> {
            send("jetbrains.com")
            send("asdf.asdf.asdf.12345.!!!")
            send("quit")
        }
        val result = process
                .map { event ->
                    when(event){
                        is StandardError -> event
                        is StandardOutput -> event.apply {
                            val x = 4;
                            when(line){
                                "Input the user name" -> process.send("groostav")
                                "Input your server name, or 'quit' to exit" -> process.send(responses.receive())
                                else -> { }
                            }
                        }
                        is ExitCode -> event
                    }
                }
                .map {
                    it.also { println(it) }
                }
                .toList()

        assertEquals(listOf(
                StandardOutput(line="Serious script(tm)(C)(R)"),
                StandardOutput(line="Input the user name"),
                StandardOutput(line="groostav"),
                StandardOutput(line="Input your server name, or 'quit' to exit"),
                StandardOutput(line="jetbrains.com"),
                StandardOutput(line="Sorry groostav, jetbrains.com is already at 54.225.64.222"), //TODO: this IP is dynamic... herp derp.
                StandardOutput(line="Input your server name, or 'quit' to exit"),
                StandardOutput(line="asdf.asdf.asdf.12345.!!!"),
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
        ), result)
    }

    private fun getLocalResourcePath(localName: String): String {
        val rsx = WindowsTests::class.java.getResource(localName)
        val resource = Paths.get(rsx.toURI()).toString()
        return resource
    }

    @Test fun `when using stream should be able to relatively easily interact with process`(){
        TODO()
    }

    @Test fun `when looking for character streams should be relatively simple`(){
        TODO()
    }

}

private fun testing(line: String){
    println(line)
}

data class DomainModel(val data: String)
