package groostav.kotlinx.exec

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

@InternalCoroutinesApi
class BasicTests {

    @Test
    fun `when command returns allowed nonzero exit code should return normally`() = runBlocking<Unit>{

        // because '1' is an expected code, and the script exited with code 1, we see that as a regular return value,
        // rather than a thrown UnexpectedExitCode exception
        val (_, code) = exec {
            command = errorAndExitCodeOneCommand()
            expectedOutputCodes = setOf(1)
        }

        assertEquals(1, code)
    }

    @Test fun `todo`(): Unit = TODO("write some cross-platform usage examples")
}