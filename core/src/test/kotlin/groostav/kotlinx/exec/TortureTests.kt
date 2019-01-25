package groostav.kotlinx.exec

import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Use fake process objects to project odd but legal behaviour from process API,
 * ensure that kotlinx.exec handles it properly
 */
class TortureTests {

    @Suppress("UNREACHABLE_CODE")
    @Test fun `when process emits exit code before emitting and closing standard out should hold process open`() = runBlocking<Unit>(TODO("need single threaded dispatcher")){

        // setup
        // make fake PID generator, process listeners, etc
        // val listenerJob = fakeListener {
        //     emit(ExitCode(0))
        //     emit(StandardOutputMessage("ahah"))
        //     stderr.close()
        //     stdin.close()
        //     //do not close stdout
        // }
        // val exec = ExecCoroutine()

        // act
        // listenerJob.join()
        // val exitCode: Int? = select {
        //     onTimeout(30) { null }
        //     exec.onAwait { it }
        // }

        // assert
        // assertNull(exitCode)
        // assertFalse(exec.isCompleted)
        // assert(exec.State == Running)

        TODO()
    }

}