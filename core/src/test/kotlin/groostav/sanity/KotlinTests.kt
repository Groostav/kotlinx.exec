package groostav.sanity

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectInstance
import kotlinx.coroutines.selects.select
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.internal.FunctionReference
import kotlin.reflect.KClass
import kotlin.reflect.KFunction1
import kotlin.reflect.jvm.jvmName
import kotlin.test.*

@InternalCoroutinesApi
class KotlinTests {

    @Ignore("unbelievable, select {} does simply abandon you!")
    @Test fun `using an empty select clause doenst just abandon you`() = runBlocking {

        val producer = produce<String> {
            select<String> {
                val x = 4;
            }

            val y = 4;
        }

        val result = producer.receiveCatching().getOrNull()

        val z = 4;
    }

    @Ignore("see https://youtrack.jetbrains.com/issue/KT-24209")
    @Test fun `when using select in producer to merge channels should operate normally`() = runBlocking<Unit> {

        val sourceOne = produce { send(1); send(2); send(3) }

        //inlining this var causes the test to pass
        var s1: ReceiveChannel<Any>? = sourceOne

        val merged = produce<Any>{
            while(isActive){ //removing this while causes the test to pass
                val next = select<Any> {
                    s1?.onReceive { it }
                }
            }
        }

        merged.receiveCatching()
        //does not reach here.
    }

    @Test fun `mssing around with eventloop`() = runBlocking {
//        val loop = EventLoop(Thread.currentThread())
//
//        val result = async(loop){
//            val x = 4;
//            x
//        }
//
//        (loop as EventLoop).processNextEvent()
//
//        val r = result.await()
//
//        assertEquals(4, r)
    }

    @Test fun `messing around with quasi suspendable eventloop`() = runBlocking {

//        val loop = EventLoop(Thread.currentThread())
//
//        val x = async(loop){ 4 }
//        val y = async(loop){ 5 }
//
//        while(true) {
//            val nextDelay = (loop as EventLoop).processNextEvent()
//            if(nextDelay == Long.MAX_VALUE) break;
//            if(nextDelay > 0) delay(nextDelay, TimeUnit.NANOSECONDS)
//        }
//
//        val (rx, ry) = x.await() to y.await()
//
//        assertEquals(4, rx)
//        assertEquals(5, ry)
    }

    @Test fun `when using unconfined context should be able to do imperitive style thread switch`() = runBlocking {

        var initialThread: String = ""
        var finalThread: String = ""

        launch(Dispatchers.Unconfined){
            initialThread = Thread.currentThread().name
            delay(1)
            finalThread = Thread.currentThread().name
        }.join()

        assertNotEquals(initialThread, finalThread)
        assertNotEquals("", initialThread)
        assertNotEquals("", finalThread)

        // I wrote this test thinking about single threaded dispatchers,
        // thinking that I could ue an event loop to improve the exception call-stack (or debugger 'pause')
        // in `exec` or `execVoid` --those with clear loop.enter() points, though we could infer it for the async one...
        // but this was naive:
        // 1. what do you do about pools? You'd have to drop the thread portion of the EventLoop
        //      --granted elizarov is clearly angling to do that, better him than me.
        // 2. stack-traces cannot be saved! Once suspended, **the stack trace is gone!!**,
        //      I know this, but I consistently forget it.
        // so, event loops: neat tool, useless to me here.
    }

    private enum class Side { Left, Right }
    infix fun Deferred<Boolean>.orAsync(right: Deferred<Boolean>) = GlobalScope.async<Boolean> {
        val left: Deferred<Boolean> = this@orAsync
        //note: I didnt take a context object.
        //`infix` might not be possible...

        // 'short circuit'
        if(left.isCompleted && left.getCompleted()) return@async true
        if(right.isCompleted && right.getCompleted()) return@async true

        //we have no choice but to wait for one of them to return
        val (side, sideTrue) = select<Pair<Side, Boolean>> {
            left.onAwait { Side.Left to it }
            right.onAwait { Side.Right to it }
        }

        return@async when {
            //if that value was true, we're done
            sideTrue -> true
            //else wait for the other to complete
            side == Side.Left -> right.await()
            side == Side.Right -> left.await()
            else -> TODO()
        }
    }

    @Test fun `when using async or and one of the two operands returns true`() = runBlocking {
        val left = CompletableDeferred<Boolean>()
        val right = CompletableDeferred<Boolean>()

        val result = left orAsync right

        delay(10)

        left.complete(true)

        assertTrue(result.await())
    }
    // I should brush up on those funny monadic law based testing systems,
    // the ability to define a behaviour and a set of inputs here would be nice...


    infix fun Deferred<Boolean>.orAsyncLazy(right: suspend () -> Boolean) = GlobalScope.async<Boolean> {
        val left: Deferred<Boolean> = this@orAsyncLazy

        // 'short circuit'
        if(left.isCompleted && left.getCompleted()) return@async true
        val leftValue = left.await()

        return@async leftValue || right()
    }

    @Test fun `when using orAsyncLazy should not start until left completes`() = runBlocking {
        val left = CompletableDeferred<Boolean>()
        val right: suspend () -> Nothing = { TODO("blam: you evaluated right eagerly!") }

        val result = left orAsyncLazy { right() }

        delay(10)

        left.complete(true)

        assertTrue(result.await())
    }

    @Test fun `when using orAsyncLazy should evaluate right when left is false`() = runBlocking {
        val left = CompletableDeferred<Boolean>()
        var evaluatedRight: Boolean = false
        val right: suspend () -> Boolean = right@ { evaluatedRight = true; return@right true }

        val result = left orAsyncLazy { right() }

        delay(10)

        left.complete(false)

        assertTrue(result.await())
        assertTrue(evaluatedRight)
    }

    @Ignore("expected behaviour is to hang")
    @Test fun `when using runblockign with parent-child coroutine and child is abandoned should never return`(){
        runBlocking {
            launch {
                delay(999_999_999)
            }
        }
        println("done!")
    }

    @Test fun `when attempting to detect cancelation of parent should let you detect it through exceptions`() = runBlocking<Unit> {

        var result: List<String> = emptyList()

        val job = launch {

            val childJob = GlobalScope.launch {
                result += "doing stuff!"
                delay(1_000)
                result += "done!"
            }

            try {
                childJob.join()
            }
            catch(ex: CancellationException){
                result += "cancelled!"
                throw ex
            }
        }

        delay(100)
        job.cancel()
        delay(10)

        assertEquals(listOf("doing stuff!", "cancelled!"), result)
    }

    @Test fun `one can detect parent job completion through onJoin`() = runBlocking{

        runBlocking {

            val scope: CoroutineScope = this@runBlocking

            val runBlockingAsJob = scope.coroutineContext[Job]!!

            GlobalScope.launch {
                select<Unit> {
                    runBlockingAsJob.onJoin { Unit }
                }

                println("detected!!")
            }

            delay(10)
        }


        delay(20)
    }

    @Test fun `one can create daemon coroutines with onjoin`() = runBlocking<Unit> {

        runBlocking {

            launchDaemon {
                println("I'm a daemon!")
                delay(20)
                println("daemon is done!")
            }

            launch {
                println("I'm a prime job!")
                delay(10)
                println("prime job is done!")
            }

            println("last line in outer job")
        }

        println("outer job returned")
    }

    private suspend fun CoroutineScope.launchDaemon(job: suspend CoroutineScope.() -> Unit){
        val runningJob = GlobalScope.launch(block = job)
        val parentJob = this.coroutineContext[Job]!!

        GlobalScope.launch {
            val cancel = select<Boolean>{
                runningJob.onJoin { false }
                parentJob.onJoin { true }
            }

            if(cancel) runningJob.cancel()
        }
    }

    public interface MyUsefulConcurrentDataStructure: Deferred<Int> {

    }

    public interface MyCoroutineScope : CoroutineScope {

    }

    @InternalCoroutinesApi
    class MyCoroutine(
            parentContext: CoroutineContext,
            active: Boolean
    ): AbstractCoroutine<Int>(parentContext, true, active), MyCoroutineScope, MyUsefulConcurrentDataStructure  {

//        val _channel: Channel<Int> = TODO()

        //region copied from ChannelCoroutine
//
//        override val cancelsParent: Boolean get() = true
//
////        val channel: Channel<Int> get() = this
//
//        override fun cancel(): Unit {
//            cancel(null)
//        }
//
//        override fun cancel0(): Boolean = cancel(null)
//
//        override fun cancel(cause: Throwable?): Boolean {
////            val wasCancelled = _channel.cancel(cause)
////            if (wasCancelled) super.cancel(cause) // cancel the job
////            return wasCancelled
//
//            return super.cancel(cause)
//        }

        //endregion

        //region copied from ProducerCoroutine
//
//        override val isActive: Boolean
//            get() = super.isActive
//
////        override fun onCompletionInternal(state: Any?, mode: Int, suppressed: Boolean) {
////            val cause = (state as? CompletedExceptionally)?.cause
////            val processed = _channel.close(cause)
////            if (cause != null && !processed && suppressed) handleExceptionViaHandler(context, cause)
////        }

        //endregion

        //region copied from DeferredCoroutine

//        override val cancelsParent: Boolean get() = true
        override fun getCompleted(): Int = TODO("delegates to internal method with bad type: getCompletedInternal() as Int")
        override suspend fun await(): Int = TODO("delegates to internal method with bad type: awaitInternal() as Int")
        override val onAwait: SelectClause1<Int> get() = onAwaitInternal as SelectClause1<Int>

        //endregion

        companion object {
            public fun CoroutineScope.doMyCoroutine(
                    context: CoroutineContext = EmptyCoroutineContext,
                    capacity: Int = 0,
                    onCompletion: CompletionHandler? = null,
                    block: suspend MyCoroutineScope.() -> Int
            ): MyUsefulConcurrentDataStructure {
                val newContext = newCoroutineContext(context)
                val coroutine = MyCoroutine(newContext, true)
                if (onCompletion != null) coroutine.invokeOnCompletion(handler = onCompletion)
                coroutine.start(CoroutineStart.DEFAULT, coroutine as MyCoroutineScope, block)
                return coroutine
            }

        }
    }

    @Ignore("written to poke at the internals of coroutines, doesnt actually check anything.")
    @Test fun `when using my coroutine should coroutine things nicely`() = with(MyCoroutine){

        val result = GlobalScope.doMyCoroutine {
            delay(100)
            val x = 4
            42
        }

        runBlocking {
            val output = result.join()

            val x = 4

            coroutineScope {

            }
        }
    }

    //ok so, a couple lessons learned:
    // 1. there's still a good chunk of 'hidden' API that means writing 'ProducerCoroutine' et al, as implied by
    // the myriad of classes that extend AbstractCoroutine, is not feasible.
    // We have to rely on the existing AbstractCoroutine implementations.
    //
    // 2. there is an enormous amount of composition-by-inheritence-by-delegation happening.
    // in general, this `AbstractCoroutine` type extends both the return type and the 'Scope' type,
    // which is in effect both the input and output of a coroutine builder. This means you get some pretty neat
    // syntax with minimal object allocation --infact, shockingly so--, but it sure is confusing.
    //
    // 3. all coroutine builders assume they have some block which is long running,
    // whose completion warrants some significance. This is kinda true for an exec() call,
    // but the obvious threading abstraction (read: the "ProcessListener") doesnt really fit with this process.
    // so these blocks are likely to be pretty simple --although, the Aggregate channel is pretty complex.

    // Ok, so, new plan, can we compose with this massive types?

    /*
    func execAsync(): RunningProcess {

        val listeners = makeListeners()
        val someChannels = ProcUnstartedChannels(listeners);

        val scope = ???

        val aggregateProducer: ReceiveChannel<ProcessEvent> = produce(scope) {
            while stuff {
                select {
                    someChannels.stdout.onReceieve {
                        //...
                }
            }
        }

        val result: Deferred<Int> = async(scope){
            someChannels.exitCode.value.await()
        }

        return ProcessImpl(
            aggregate = aggregateProducer,
            result = result,
            stdout = someChannels.stdout.openSubscription().tail(buffer)
            stderr = someChannels.stderr.openSubscription().tail(errBuffer)
        )
    }
    */


    //for reference:
    //region copied from Produce.kt

//    @InternalCoroutinesApi
//    public fun <E> CoroutineScope.produce(
//            context: CoroutineContext = EmptyCoroutineContext,
//            capacity: Int = 0,
//            onCompletion: CompletionHandler? = null,
//            @BuilderInference block: suspend ProducerScope<E>.() -> Unit
//    ): ReceiveChannel<E> {
//        val channel = Channel<E>(capacity)
//        val newContext = newCoroutineContext(context)
//        val coroutine = ProducerCoroutine(newContext, channel)
//        if (onCompletion != null) coroutine.invokeOnCompletion(handler = onCompletion)
//        coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
//        return coroutine
//   }

    //endregion

    @ExperimentalCoroutinesApi
    @Test fun `when using kotlin method reference should get parent class of method trivially`(){
        val x: KFunction1<*, *> = (MaindThingy::main)

        //I think this is as good as it gets...
        val instanceName= ((x as FunctionReference).boundReceiver::class as KClass<*>).jvmName

        assertEquals("groostav.sanity.KotlinTests\$MaindThingy", instanceName)
    }
    object MaindThingy{
        @JvmStatic fun main(args: Array<String>){
            TODO()
        }
    }


    // so the defined behaviour from `launch` is to hold the "run-blocking" dispatch loop open. Interesting.
    @Ignore("expected behaviour is to hang")
    @Test fun `a launch block that never completes holds its parent open`() = runBlocking<Unit> {
        launch { while(true) { Thread.sleep(200) } }
    }

    @Ignore("expected behaviour is to hang")
    @Test fun `a launch block that checks its cancellation still holds its parent open`() =  runBlocking<Unit> {
        launch {
            while(isActive) {
                delay(200)
            }
        }
    }

    @Test fun `when using a rendezvous channel can see through to caller if configured`() = runBlocking{

        val channel = Channel<String>(RENDEZVOUS)
        var exception: Exception? = null

        val producer = GlobalScope.launch(Dispatchers.IO){
            hardpoint {
                channel.send("hello!")
            }
        }

        val consumer = GlobalScope.launch(Dispatchers.Unconfined){
            val next = channel.receive()

            exception = Exception("blam!!")
        }

        consumer.join()
        producer.join()

        assertTrue(
                hardpointFrame in (exception?.stackTrace ?: emptyArray<StackTraceElement>()),
                "expected \n$hardpointFrame\nin\n${exception?.stackTrace?.joinToString("\n  at ")}"
        )

        // ok so this is interesting. It seems that both the parent event loop and the `send` call
        // will attempt to dispatch the `receive` continuation.
        // if the `send` call gets it, then the code is nice and debuggable and hardpoint is on stack
        // if the parent event loop gets it, its not.
        // on my machine it seems biased toward the parent event loop, I can only get the `send` call to dispatch under the debugger.
    }

    var hardpointFrame: StackTraceElement? = null
    private suspend fun <R> hardpoint(block: suspend() -> R){
        if(hardpointFrame == null){
            hardpointFrame = StackWalker.getInstance().walk { it.findFirst() }.orElse(null)?.toStackTraceElement()
        }
        block()
    }

    @Test fun `when attempting to join on ignorant launch block should keep job open`() = runBlocking<Unit>{
        val countdown = CompletableDeferred<Unit>()

        val job = GlobalScope.launch(Dispatchers.IO) {
            countdown.complete(Unit)
            Thread.sleep(450_000)
        }
        countdown.await()

        //act
        job.cancel()
        withTimeoutOrNull(200){
            job.join()
        }

        //assert
        assertFalse(job.isCompleted)

        // ok so, cancel() does effectively just set a flag
        // join() is the thing that takes a while because
        // C:/Users/Geoff/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-common/1.0.1/16382dce10af5c4159654272de71e8c0efe854c7/kotlinx-coroutines-core-common-1.0.1-sources.jar!/JobSupport.kt:502
        // JobSupport::joinSuspend (JobSupport.kt:502)
        // will suspend until the block finishes.
        // thus, to get "nice" behaviour where ExecCoroutine doesnt exit until the process is dead,
        // you need to fire the killAsync() from within the suspend block, not from an onCancellation listener.
        // which "must be fast and lock free".
    }

    @Test fun `when decoding a string fragment should be manageable`(){
        // ok, so my thinking is that we extract the concept of UTF-8 encoding of standard-out/err out of ExecCoroutine
        // and have ExecCoroutine operate on this concept of ByteArray Chunks
        // this would make it more useful for doing things like passing binary formats through input and output
        // but it would require us to decode byte array chunks, which won't necessarily
        // be delimited on byte boundaries
        // (IE; if you have the string [8-bit-utf-char][16-bit-utf-char])
        // and you read a byte-array of 2 elements, you'd get a truncated first-half of the second (16 bit) char.
        //
        // so if we do this encoding of chunks, what does Charsets.decode(chunkWithTruncatedLastChar) do?
        TODO()
    }

    @Test fun `when starting a job as the child of a cancelled process should work anyways`() = runBlocking<Unit>(Dispatchers.IO){

        val started = CountDownLatch(1)
        val proceedToLaunchChild = CountDownLatch(1)
        var child: Job? = null
        var ran: Boolean = false

        val cancellingParent = launch {
            started.countDown()
            proceedToLaunchChild.await()

            child = launch(start = CoroutineStart.ATOMIC) {
                ran = true
            }
        }
        started.await()

        //act
        cancellingParent.cancel()
        proceedToLaunchChild.countDown()
        cancellingParent.join()
        child?.join()

        // assert
        assertTrue(ran, "the coroutine $cancellingParent can start running child job after it was cancelled")
        // interestingly:
        assertFalse(child in cancellingParent.children, "the child coroutine:\n$child\nwas not a child of the parent\n$cancellingParent")
    }

    @Test fun `when asking powershell things`(){
        val process = ProcessBuilder()
            .command("powershell.exe", "-Command", "Get-Process")
            .start()

        val lines = process.inputStream.reader().readLines()

        assertTrue(lines.isNotEmpty())
    }
}

