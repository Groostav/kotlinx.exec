package groostav.kotlinx.exec

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@InternalCoroutinesApi
class JoinTests {

    @Test
    fun `when exiting normally should perform orderly shutdown`(): Unit = runBlocking {
        //setup
        val process = execAsync { command = completableScriptCommand() }

        val results = Collections.synchronizedList(ArrayList<String>())

        //act
        val procJoin = launch { process.join(); results += "procJoin" }
        val aggregateChannelJoin = launch { process.toList(); results += "aggregateChannelJoin" }

        process.send("OK")
        process.close()

        procJoin.join(); aggregateChannelJoin.join()

        //assert
        assertEquals(listOf("aggregateChannelJoin", "procJoin"), results)
        assertNotListed(process.processID)
    }

    @Test
    fun `when calling join twice shouldnt deadlock`() = runBlocking {
        //setup
        val runningProcess = execAsync {
            command = emptyScriptCommand()
        }

        //act
        runningProcess.join()
        runningProcess.join()

        //assert
        assertTrue(runningProcess.isCompleted)
        assertFalse(runningProcess.isActive)
        assertNotListed(runningProcess.processID)

        // I'd like this, but elizarov's own notes say its not a requirement
        // https://stackoverflow.com/questions/48999564/kotlin-wait-for-channel-isclosedforreceive
//        assertTrue(runningProcess.isClosedForReceive)
    }


    /**
     * This one is really tricky. This library treats [RunningProcess] instances as
     * closeable resources.
     *
     * **if you start a process, and that process hangs, `kotlinx.exec` will prevent the parent coroutine from completing!**
     */
    @Test fun `when abandoning a process should hold parent scope open`() = runBlocking<Unit>{
        // interesting behaviour: in moving from coroutines 0.X to 1.0 (ie adding parent job scope)
        // I simply attached everything that this library did to the provided parent scope
        // the result was that abandoning hanging processes would hang the parent job.
        // At first this was a problem,
        // but thinking about it, its probably best that abandon processes keep something open in their parent
        // process since an abandon process is effectively a leaked process.

        // Thus, to avoid "leaking" processes, we get this behaviour:
        var actionSequence: List<String> = emptyList()
        val jobThatSpawnsSubProcess = launch {
            val proxy = this.execAsync {
                command = hangingCommand()
            }

            actionSequence += "started sub-process"

            // note: we never explicitly synchronize on `proxy.await()` or similar.
            // meaning this job will collapse but the job wont change to "finished"
        }

        //act 1: wait for the job to start
        delay(10)

        //assert 1: the "started sub-process" was added but `jobThatSpawnsSubProcess` is not done.
        assertEquals(listOf("started sub-process"), actionSequence)
        assertFalse(jobThatSpawnsSubProcess.isCompleted)

        //act 2: start another job that depends on the first & wait for it to start
        val jobThatDependsOnPreviousJobUsingProcess = launch {
            jobThatSpawnsSubProcess.join()

            actionSequence += "moved past sub-process parent job!"
        }
        delay(10)

        //assert 2: still in the same state as assert 1, second job wont have published its message
        assertEquals(listOf("started sub-process"), actionSequence)
        assertFalse(jobThatSpawnsSubProcess.isCompleted)
        assertFalse(jobThatDependsOnPreviousJobUsingProcess.isCompleted)

        //act 3: add something to the sequence to note where here, then cancel the first job, and wait for it to finish
        actionSequence += "sub-process status check: isComplete=${jobThatSpawnsSubProcess.isCompleted}"
        jobThatSpawnsSubProcess.cancel()
        delay(10)

        //assert 3: assert that the statement we made happened before the job finished, and that all jobs are finished.
        assertEquals(listOf(
                "started sub-process",
                "sub-process status check: isComplete=false",
                "moved past sub-process parent job!"
        ), actionSequence)
        assertTrue(jobThatSpawnsSubProcess.isCompleted)
        assertTrue(jobThatDependsOnPreviousJobUsingProcess.isCompleted)
    }


}