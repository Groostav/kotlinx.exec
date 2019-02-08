package groostav.kotlinx.exec

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.selects.select
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Use fake process objects to project odd but legal behaviour from process API,
 * ensure that kotlinx.exec handles it properly
 */
@InternalCoroutinesApi
internal class TortureTests {

    @Test fun `when process emits exit code before emitting and closing standard out should hold process open`() = runBlocking<Unit> {

        // setup
        val interceptors = Interceptors()
        val exec = makeStartedExecCoroutine(interceptors)

        // act
        interceptors.apply {
            emit(ExitCode(99))
            emit(StandardOutputMessage("ahah"))
            standardError.close()
            standardInput.close()
        }
        val firstMessage = select<String?> {
            onTimeout(1500) { null }
            exec.onAwait { "exit code $it" }
            exec.onReceive { it.formattedMessage }
        }
        delay(500)

        // assert
        assertEquals("ahah", firstMessage)
        assertFalse(exec.isCompleted)
        assertFalse(exec.state.outEOF, "expected stdoutEOF=false, but state=${exec.state}")
    }

    @Test fun `when process emits exit code before emitting and closing standard error should hold process open`(): Unit = runBlocking {

        // setup
        val interceptors = Interceptors()
        val exec = makeStartedExecCoroutine(interceptors)

        // act
        interceptors.apply {
            emit(ExitCode(99))
            emit(StandardOutputMessage("ahah"))
            standardOutput.close()
            standardInput.close()
        }
        val firstMessage = select<String?> {
            onTimeout(1500) { null }
            exec.onAwait { "exit code $it" }
            exec.onReceive { it.formattedMessage }
        }
        delay(500)

        // assert
        assertEquals("ahah", firstMessage)
        assertFalse(exec.isCompleted)
        assertFalse(exec.state.errEOF, "expected stderrEOF=false, but state=${exec.state}")
    }

    val ExecCoroutine.State.outEOF: Boolean get() = when(this){
        is ExecCoroutine.State.Running -> stdoutEOF
        is ExecCoroutine.State.Completed -> stdoutEOF
        else -> false
    }
    val ExecCoroutine.State.errEOF: Boolean get() = when(this){
        is ExecCoroutine.State.Running -> stderrEOF
        is ExecCoroutine.State.Completed -> stderrEOF
        else -> false
    }

    internal object FakePIDGenerator: ProcessIDGenerator {

        private val lastPID = AtomicInteger(0)

        override fun findPID(process: Process) = lastPID.incrementAndGet()
    }

    internal class Interceptors {

        val standardInput = Channel<Char>()
        val standardOutput = Channel<Char>()
        val standardError = Channel<Char>()
        val exitCode = CompletableDeferred<Int>()

        suspend fun emit(event: ProcessEvent): Unit = when(event){
            is StandardOutputMessage -> {
                event.line.forEach { standardOutput.send(it) }
                standardOutput.send('\n')
            }
            is StandardErrorMessage -> {
                event.line.forEach { standardError.send(it) }
                standardError.send('\n')
            }
            is ExitCode -> { exitCode.complete(event.code); Unit }
        }

        val listenerFacade = object: ProcessListenerProvider.Factory {
            override fun create(process: Process, pid: Int, config: ProcessConfiguration) = object: ProcessListenerProvider {
                override val standardErrorChannel = Supported(standardError)
                override val standardOutputChannel = Supported(standardOutput)
                override val exitCodeDeferred = Supported(exitCode)
            }
        }
        val controlFacade = object: ProcessControlFacade.Factory {
            override fun create(config: ProcessConfiguration, process: Process, pid: Int) = Supported(object: ProcessControlFacade{
                override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }
            })
        }
    }

    private fun makeStartedExecCoroutine(interceptors: Interceptors) = ExecCoroutine(
            ProcessConfiguration().apply {
                command = listOf("asdf.exe")
            },
            EmptyCoroutineContext,
            true,
            FakePIDGenerator,
            interceptors.listenerFacade,
            interceptors.controlFacade,
            makeProcessBuilder@{ args ->
                mockk {
                    every { environment() } returns HashMap()
                    every { directory(any()) } returns this
                    every { start() } returns mockk()
                }
            },
            makeInputStreamActor@{ args -> Channel(UNLIMITED) },
            Channel(UNLIMITED),
            Channel(UNLIMITED)
    ).also {
        it.prestart()
        it.kickoff()
        it.start(CoroutineStart.DEFAULT, it, ExecCoroutine::waitFor)
    }
}
