package groostav.kotlinx.exec

import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiprocessTests {

    @Test fun `todo`(){
        // firstly, the java.lang.ProcessBuilder documents that multiple starts calls => multiple running procs.
        // Is this useful? Should we emulate it?
    }

    @Ignore("see https://github.com/Groostav/kotlinx.exec/issues/7")
    @Test fun `when using two processes should be able to pipe output of one into another simply`(){
        val source = execAsync { TODO() }
        val sink = execAsync { TODO() }

        val linked = source pipeTo sink

        //linked should have input of source, and output of sink.
        assertEquals(linked.standardInput, source.standardInput)
        assertEquals(linked.standardOutput, sink.standardOutput)
        assertEquals(linked.standardError, sink.standardError)
    }

    infix fun RunningProcess.pipeTo(sink: RunningProcess): RunningProcess = TODO()
    //hmm.. or maybe this makes more sense at ProcessBuilder time.
    // That way we dont get late data and get static gaurentees about 'promptness'
}