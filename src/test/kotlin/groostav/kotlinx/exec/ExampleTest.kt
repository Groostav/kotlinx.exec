package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldEqual
import org.junit.Assert.assertEquals
import org.junit.Test


class ExamplesTest {

    @Test
    fun `when running calc should get a calc`() = runBlocking {
        val code = exec("calc.exe")
    }

    @Test
    fun `when running simple echo statement should properly redirect`() = runBlocking {

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

        //TODO: I dont know where these extra quotes are coming from..
        messagesList.shouldEqual(listOf(
                DomainModel("\"hello command line!\""),
                DomainModel("exit code: 0")
        ))
        assertEquals(0, exitCode)
    }

    @Test fun `while trying to recover a stuck command`() = runBlocking<Unit> {
        val process = execAsync("powershell.exe", "-Command", "while(\$true) { echo \"running!\"; Sleep -m 500 }")

        delay(5000)

        process.kill()

        val stdout = process.standardOutput.toList()
        val result = process.exitCode.await()

        result.shouldEqual(1)
        stdout.shouldContain("running!")
    }

}

fun ReceiveChannel<Char>.lines(): ReceiveChannel<String> = TODO()

data class DomainModel(val data: String)