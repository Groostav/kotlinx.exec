package groostav.kotlinx.exec

import org.junit.Test

class CancelTests {

    @Test fun todo(): Unit = TODO("""
        so, I think we should have some tests that clearly define the cancellation behaviour.

        In particular, should cancellation/completion of a parent coroutine result in a sub-process being killed?
        Or should we simply assume all uses of this library are safely "fire-and-forget" style programs?

        going back to principals: Coroutines are designed to make for synchronous concurrent programming.
        I think we should assume that subProcess.await() is the norm. If somebody starts a sub-process,
        but does not join on its result, and the parent coroutine subsequently "ends", attempting to cancel
        our exec-coroutine: then we should assume some kind of unhappy path.

        What exactly is that unhappy path?

    """.trimIndent())

    @Test fun `when cancelling an exec job should kill subprocess`(){
        TODO()
    }

    @Test fun `when cancelling parent of an exec job should kill subprocess`(){
        TODO()
    }
}