package groostav.kotlinx.exec

import errorAndExitCodeOneCommand
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test
import printWorkingDirectoryCommand
import java.nio.file.Paths
import kotlin.test.assertEquals

class ConfigurationTests {

    @Test fun `when setting working directory resulting subprocess should see that directory`() = runBlocking {
        val (result, code) = exec {
            command = printWorkingDirectoryCommand()

            workingDirectory = Paths.get(System.getProperty("java.io.tmpdir"))
        }

        val expected = listOf(Paths.get(System.getProperty("java.io.tmpdir")).toString())
        assertEquals(expected, result)
    }

    @Test fun `when running with specified environment parameter should see that environment parameter`() = runBlocking<Unit> {
        TODO()
    }

    @Test fun `when command returns allowed nonzero exit code should return normally`() = runBlocking<Unit>{
        val (_, code) = exec {
            command = errorAndExitCodeOneCommand()
            expectedOutputCodes = setOf(1)
        }

        assertEquals(1, code)
    }

}