package groostav.kotlinx.exec

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `when using lazy start should not actualy start until joined`() = runBlocking<Unit>{

        //setup
        val proc = execAsync(CoroutineStart.LAZY) {
            command = emptyScriptCommand()
        }

        val procWasActive = proc.isActive
        val procWasComplete = proc.isCompleted

        //act
        proc.start()
        val code = proc.await()

        //assert 2
        assertEquals(0, code)
        assertFalse(procWasActive)
        assertFalse(procWasComplete)
        assertFalse(proc.isActive)
        assertTrue(proc.isCompleted)
    }

    @Test fun `todo`(): Unit = TODO("write some cross-platform usage examples")
}