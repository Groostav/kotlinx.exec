package groostav.kotlinx.exec

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


}