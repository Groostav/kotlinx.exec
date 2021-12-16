package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * Use fake process objects to project odd but legal behaviour from process API,
 * ensure that kotlinx.exec handles it properly
 */
@InternalCoroutinesApi
@Ignore
internal class TortureTests {

    @Test fun `when process emits exit code before emitting and closing standard out should hold process open`() = runBlocking<Unit> {

        TODO("""
            these tests are probably worth saving since they expose strange logic of the underlying machine
            but that would require a testable platform underneath the ExecCoroutine, which is pretty tricky. 
        """.trimIndent())
//        // setup
//        val interceptors = Interceptors()
//        val exec = makeStartedExecCoroutine(interceptors)
//
//        // act
//        interceptors.apply {
//            emit(ExitCode(0))
//            emit(StandardOutputMessage("ahah"))
//            standardError.close()
//            standardInput.close()
//        }
//        val firstMessage = select<String?> {
//            onTimeout(1500) { null }
//            exec.onAwait { "exit code $it" }
//            exec.onReceive { it.formattedMessage }
//        }
//        delay(500)
//
//        // assert
//        assertEquals("ahah", firstMessage)
//        assertFalse(exec.isCompleted)
//        assertFalse(exec.state.outEOF, "expected stdoutEOF=false, but state=${exec.state}")
    }

    @Test fun `when process emits exit code before emitting and closing standard error should hold process open`(): Unit = runBlocking {
        // setup
        val interceptors = Interceptors()
        val exec = makeStartedExecCoroutine(interceptors)

        // act
        val fakeProcess = FakeProcess().apply {

        }
        interceptors.apply {
            emit(ExitCode(0))
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

    @Test fun `when process completes without anybody waiting for it should go into completed state anyways`() = runBlocking<Unit> {
        TODO()
//        // setup
//        val interceptors = Interceptors()
//        val exec = makeStartedExecCoroutine(interceptors)
//
//        // act
//        interceptors.apply {
//            standardOutput.close()
//            standardInput.close()
//            standardError.close()
//            exitCode.complete(0)
//        }
//        delay(300)
//
//        // assert
//        assertTrue(exec.isCompleted)
//        assertEquals(ExecCoroutine.State.Completed(1, 0, null, null), exec.state)
    }

//    val ExecCoroutine.State.outEOF: Boolean get() = when(this){
//        is ExecCoroutine.State.Running -> stdoutEOF
//        is ExecCoroutine.State.WindingDown -> stdoutEOF
//        else -> false
//    }
//    val ExecCoroutine.State.errEOF: Boolean get() = when(this){
//        is ExecCoroutine.State.Running -> stderrEOF
//        is ExecCoroutine.State.WindingDown -> stderrEOF
//        else -> false
//    }

//    internal object FakePIDGenerator: ProcessIDGenerator {
//        override fun findPID(process: Process) = 1
//    }

    internal class Interceptors {
//        val standardInput = Channel<Char>()
//        val standardOutput = Channel<Char>()
//        val standardError = Channel<Char>()
//        val exitCode = CompletableDeferred<Int>()
//
//        suspend fun emit(event: ProcessEvent): Unit = when(event){
//            is StandardOutputMessage -> {
//                event.line.forEach { standardOutput.send(it) }
//                standardOutput.send('\n')
//            }
//            is StandardErrorMessage -> {
//                event.line.forEach { standardError.send(it) }
//                standardError.send('\n')
//            }
//            is ExitCode -> { exitCode.complete(event.code); Unit }
//        }
//
//        val listenerFacade = object: ProcessListenerProvider.Factory {
//            override fun create(process: Process, pid: Int, config: ProcessConfiguration) = object: ProcessListenerProvider {
//                override val standardErrorChannel = Supported(standardError)
//                override val standardOutputChannel = Supported(standardOutput)
//                override val exitCodeDeferred = Supported(exitCode)
//            }
//        }
//        val controlFacade = object: ProcessControlFacade.Factory {
//            override fun create(config: ProcessConfiguration, process: Process, pid: Int) = Supported(object: ProcessControlFacade{
//                override fun tryKillGracefullyAsync(includeDescendants: Boolean) = Unsupported("testing")
//                override fun killForcefullyAsync(includeDescendants: Boolean)= Unsupported("testing")
//            })
//        }
    }

    private fun makeStartedExecCoroutine(interceptors: Interceptors): ExecCoroutine = TODO(
//            ProcessConfiguration().apply {
//                commandLine = listOf("asdf.exe")
//            },
//            EmptyCoroutineContext,
//            true,
//            FakePIDGenerator,
//            interceptors.listenerFacade,
//            interceptors.controlFacade,
//            makeProcessBuilder@{ args ->
//                mockk {
//                    every { environment() } returns HashMap()
//                    every { directory(any()) } returns this
//                    every { start() } returns mockk()
//                }
//            },
//            makeInputStreamActor@{ args -> Channel(UNLIMITED) },
//            Channel(UNLIMITED),
//            Channel(UNLIMITED)
//    ).also {
//        it.prestart()
//        it.kickoff()
//        it.start(CoroutineStart.DEFAULT, it, ExecCoroutine::waitFor)
    )
}

@ExperimentalCoroutinesApi
class FakeProcess: Process() {

    val result = CompletableDeferred<Int>()
    val stdin = Channel<Byte>(capacity = UNLIMITED)
    val stderr = Channel<Byte>(capacity = UNLIMITED)
    val stdout = Channel<Byte>(capacity = UNLIMITED)

    //standard-input
    override fun getOutputStream(): OutputStream = object: OutputStream() {
        override fun write(b: Int) { stdin.trySend(b.toByte()) }
    }

    override fun getInputStream(): InputStream = object: InputStream() {
        //performance here is terrible but... for a testing platform, do I care?
        // yeahhhhh but creating an event queue to fetch a single byte? thats a bridge too far...
//        fail;
        override fun read(): Int {
            val result: Byte = (stdout.tryReceive().getOrNull()
                ?: runBlocking { (stdout.receiveCatching().getOrNull() ?: -1) })
//            return result
        }
    }
    override fun getErrorStream(): InputStream = object: InputStream() {
        override fun read(): Int = runBlocking { (stderr.receiveCatching().getOrNull() ?: -1).toInt() }
    }

    override fun waitFor(): Int = runBlocking { result.await() }
    override fun exitValue(): Int = if( ! result.isCompleted) throw IllegalThreadStateException() else result.getCompleted()

    override fun destroy() {
        result.complete(-1)
    }

    override fun toHandle() = object: ProcessHandle {

        override fun compareTo(other: ProcessHandle?): Int {
            TODO("Not yet implemented")
        }

        override fun pid(): Long = -2
        override fun parent(): Optional<ProcessHandle> = Optional.ofNullable(null)
        override fun children(): Stream<ProcessHandle> = Stream.empty()
        override fun descendants(): Stream<ProcessHandle> = Stream.empty()

        override fun info(): ProcessHandle.Info = TODO()

        override fun onExit(): CompletableFuture<ProcessHandle> {
            TODO("Not yet implemented")
        }

        override fun supportsNormalTermination(): Boolean = "windows" !in System.getProperty("os.name").lowercase()
        override fun destroy(): Boolean = true
        override fun destroyForcibly(): Boolean = true

        override fun isAlive(): Boolean {
            TODO("Not yet implemented")
        }
    }
}
}