package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
import org.junit.Ignore
import org.junit.Test
import java.io.IOException
import kotlin.test.*
import java.lang.ProcessBuilder as JProcBuilder

class KotlinTests {

    @Ignore("unbelievable, select {} does simply abandon you!")
    @Test fun `using an empty select clause doenst just abandon you`() = runBlocking {

        val producer = produce<String> {
            select<String> {
                val x = 4;
            }

            val y = 4;
        }

        val result = producer.receiveOrNull()

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

        merged.receiveOrNull()
        //does not reach here.
    }

    @Ignore("https://youtrack.jetbrains.net/issue/KT-25716")
    @Test fun `one two`(){
//        class Message(val outputQuestionLine: String, val response: CompletableDeferred<String> = CompletableDeferred())
//
//        val localDecoder = listOf<Message>()
    }

    @Test fun `when opening subscription after member already published should suspend`() = runBlocking {

        val channel = ConflatedBroadcastChannel<Unit>()
        channel.send(Unit)

        val r = withTimeoutOrNull(200) {
            channel.openSubscription().receive()
        }

        assertNotNull(r)
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

    @Test fun `when using simple integer progression as set should get reasonable responses`(){
        val oneToSevenStepTwoSet = (1 .. 7 step 2).asSet()

        assertTrue(1 in oneToSevenStepTwoSet)
        assertTrue(3 in oneToSevenStepTwoSet)
        assertTrue(5 in oneToSevenStepTwoSet)
        assertTrue(7 in oneToSevenStepTwoSet)
        assertTrue(oneToSevenStepTwoSet.all { it in oneToSevenStepTwoSet })
        assertFalse(0 in oneToSevenStepTwoSet)
        assertFalse(2 in oneToSevenStepTwoSet)
        assertFalse(6 in oneToSevenStepTwoSet)
        assertFalse(8 in oneToSevenStepTwoSet)
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

    @Test @Ignore fun `when writing to stdin of dead process should XYZ`(){
        //setup
        val proc = JProcBuilder("calc.exe")
        val running = proc.start()
        val result = running.waitFor()

        //act & assert
        running.outputStream.write(42) //nothing, it just buffers it.
        assertThrows<IOException> { running.outputStream.flush() } //blam
    }

    @Ignore("I dont understand why this isnt a compiler error.")
    //shouldnt it be "cannot use inferred return type of 'Unit' for 'x: (Int) -> Int' or some such?
    @Test fun `things`(){
        asdf { when(it) {} }
    }

    fun asdf(x: (Int) -> Int): Unit {
        println("result from x(10) is ${x(10)}")
    }
}
