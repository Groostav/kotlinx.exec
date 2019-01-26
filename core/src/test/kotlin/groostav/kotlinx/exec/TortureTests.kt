package groostav.kotlinx.exec

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.full.cast
import kotlin.test.assertEquals

/**
 * Use fake process objects to project odd but legal behaviour from process API,
 * ensure that kotlinx.exec handles it properly
 */
@InternalCoroutinesApi
class TortureTests {

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
            is StandardOutputMessage -> event.line.forEach { standardOutput.send(it) }
            is StandardErrorMessage -> event.line.forEach { standardError.send(it) }
            is ExitCode -> { exitCode.complete(event.code); Unit }
        }

        val listenerFacade = object: ProcessListenerProvider.Factory {
            override fun create(process: Process, pid: Int, config: ProcessBuilder) = object: ProcessListenerProvider {
                override val standardErrorChannel = Supported(standardError)
                override val standardOutputChannel = Supported(standardOutput)
                override val exitCodeDeferred = Supported(exitCode)
            }
        }
        val controlFacade = object: ProcessControlFacade.Factory {
            override fun create(config: ProcessBuilder, process: Process, pid: Int) = Supported(object: ProcessControlFacade{
                override fun tryKillGracefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun killForcefullyAsync(includeDescendants: Boolean): Maybe<Unit> {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }
            })
        }
    }

    @Suppress("UNREACHABLE_CODE")
    @Test fun `when process emits exit code before emitting and closing standard out should hold process open`() = runBlocking<Unit> {

        // setup
        val interceptors = Interceptors()
        val exec = ExecCoroutine(
                ProcessBuilder().apply {
                    command = listOf("asdf.exe")
                },
                EmptyCoroutineContext,
                true,
                FakePIDGenerator,
                interceptors.listenerFacade,
                interceptors.controlFacade,
                mockk<(List<String>) -> java.lang.ProcessBuilder>(relaxed = true){

                    fail; //oh man, inline this, and it gives me a polluted heap, change it to try to push the error up and i get this.
                    // mocking frameworks are aweful aweful things.
                    // why dont they just use reflection and kotlins nice member-reference syntax to mock? Why all the lambdas?

                    val result = mockk<java.lang.ProcessBuilder> {
                        every { environment() } returns HashMap()
                        every { directory() } returns File("MOCK/FILE/PATH")
                    }
                    every { this@mockk.invoke(any()) } returns (java.lang.ProcessBuilder::class.cast(result))
                },
                Channel(),
                Channel()
        ).also {
            it.prestart()
            it.kickoff()
            it.start(CoroutineStart.DEFAULT, it, ExecCoroutine::waitFor)
        }

        // act
        interceptors.apply {
            emit(ExitCode(0))
            emit(StandardOutputMessage("ahah"))
            standardError.close()
            standardInput.close()
        }
        val exit = select<Int?> {
            onTimeout(30) { null }
            interceptors.exitCode.onAwait { it }
        }


        assertEquals(true, false)
        // assert
        // assertNull(exitCode)
        // assertFalse(exec.isCompleted)
        // assert(exec.State == Running)

        TODO()
    }

}