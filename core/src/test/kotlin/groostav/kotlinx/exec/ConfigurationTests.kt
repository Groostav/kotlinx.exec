package groostav.kotlinx.exec

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Paths
import java.util.*
import kotlin.test.assertEquals

class ConfigurationTests {

    @Test fun `when setting working directory resulting subprocess should see that directory`() = runBlocking {
        val (result, code) = exec(
            commandLine = printWorkingDirectoryCommand(),
            workingDirectory = Paths.get(System.getProperty("java.io.tmpdir"))
        )

        val expected = listOf(Paths.get(System.getProperty("java.io.tmpdir")).toString(), "Process finished with exit code 0")
        assertEquals(expected, result)
    }

    @Test fun   `when running with specified environment parameter should see that environment parameter`() = runBlocking<Unit> {
        val uniqueKey = UUID.randomUUID()
        val (result, code) = exec(
            commandLine = printASDFEnvironmentParameterCommand(),
            environment = InheritedDefaultEnvironment + ("ASDF" to "1234-$uniqueKey")
        )

        assertEquals(listOf("ASDF is '1234-$uniqueKey'", "Process finished with exit code 0"), result)
    }

    @Test fun `when command returns allowed nonzero exit code should return normally`() = runBlocking<Unit>{
        val (_, code) = exec(
            commandLine = errorAndExitCodeOneCommand(),
            expectedOutputCodes = setOf(1)
        )

        assertEquals(1, code)
    }
}