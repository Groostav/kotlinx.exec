package groostav.kotlinx.exec

import org.junit.Test


class AspectHelpers {
    @Test fun `stuff`(): Unit = TODO("""
        Can we apply some aspect oriented programming techniques to help us cover some things?
        For example, buffers (or threads, in the case of blocking listeners) must be acquired before the `start()` call
        else we might lose some data from the process.
        Can we insert some kind of telemetry to track buffer allocations, thread allocations, and start() calls,
        then assert on them in some kind of shared way?

        what needs to be covered:
        1. as above, buffer allocation happens before pb.start() call
        2. as above, thread allocation happens before pb.start() call
        3. that `process.toList()` always complete(d) before `process.join()`
           -- because the aggregate channel should always complete before the exit value
        4. if the process joined, its PID should not be listed as running by the OS

    """.trimIndent())
}