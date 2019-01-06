package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.*
import kotlin.coroutines.CoroutineContext

//TODO: why isn't this part of kotlinx.coroutines already? Something they know I dont?
internal fun ReceiveChannel<Char>.lines(
        delimiters: List<String> = listOf("\r", "\n", "\r\n"),
        context: CoroutineContext = Unconfined
): ReceiveChannel<String> {
    val result = GlobalScope.produce<String>(context + CoroutineName("lines{$this@lines}")){

        trace { "starting lines-${this@lines}" }

        val buffer = StringBuilder(80)

        val stateMachine = LineSeparatingStateMachine(delimiters)

        this@lines.consumeEach { nextChar ->

            val newState = stateMachine.translate(nextChar)

            when (newState) {
                State.NoMatch -> {
                    buffer.append(nextChar)
                }
                State.NewMatch -> {
                    val line = buffer.takeAndClear()
                    send(line)
                }
                State.ContinuedMatch -> {
                    //noop, drop the character.
                }
            }
        }
        if (!buffer.isEmpty()) {
            send(buffer.takeAndClear())
        }

        trace { "finished lines-${this@lines}" }
    }

//    return object: ReceiveChannel<String> by result {
//        override fun toString() = "lines-${this@lines}"
//    }
    return result;
}

private fun StringBuilder.takeAndClear(): String = toString().also { setLength(0) }

private class LineSeparatingStateMachine(delimiters: List<String>) {
    val delimeterMatrix: Array<CharArray> = delimiters.map { it.toCharArray() }.toTypedArray()
    var currentMatchColumn: Int = -1
    val activeRows: BitSet = BitSet().apply { set(0, delimiters.size) }

    var previousState: State = State.NoMatch

    fun translate(next: Char): State {

        // strategy:
        // array delimieters into a jaggad matrix,
        // where rows are 'delimiter strings' (eg \r\n)
        // keep an index indicating the current 'column' being checked.
        // and keep a set of "still feasible rows" (activeRows),
        // these are the indexes of rows that still match the provided character.
        // as we see new characters, increment the column index and see if
        // any of the rows at that index match the current character.

        // for example, given we have delimeters d1="\r\n" and d2="\n",
        // and we're parsing the string "a\r\nb", we would do
        //
        // initialization:
        //   activeRows initialized to setOf(indexOf(d1), indexOf(d2)) == setOf(0, 1)
        //   currentMatchColumn = -1,
        //   currentState = NoMatch
        //
        // translate('a') =>
        //    activeRows => removes d1 because \r isnt 'a', d2 because '\n' isnt 'a' => == emptySet()
        //    newstate is NoMatch because activeRows.isEmpty()
        //    currentMatchColumn set to -1 because activeRows isEmpty
        //    activeRows reset to setOf(0, 1)
        // translate('\r') =>
        //    activeRows keeps d1, removes d2 because '\r' isnt '\n'
        //    nextState is NewMatch because activeRows.any() and previousState == NoMatch
        //    currentMatchColumn is 0
        // translate('\n') =>
        //    activeRows keeps d1 because '\n' is '\n', no changes
        //    newState is ContinuedMatch because activeRows.any() and previousState == NewMatch
        //    currentMatchColumn is 1
        // translate('b') =>
        //    activeRows removes d1 because 'b' isnt '\n'
        //    newState is NoMatch because activeRows isEmpty
        //    activeRows reset to setOf(0, 1)
        //    currentMatchColumn is reset to -1

        //update active-rows
        moveNext(next)

        if(activeRows.isEmpty && currentMatchColumn != 0){
            //try a new match
            reset()
            moveNext(next)
        }

        //generate new state
        val nextState = when {
            activeRows.isEmpty() -> State.NoMatch
            currentMatchColumn == 0 -> State.NewMatch
            previousState == State.NoMatch -> State.NewMatch
            previousState == State.NewMatch -> State.ContinuedMatch
            previousState == State.ContinuedMatch -> State.ContinuedMatch
            else -> TODO()
        }

        if(nextState == State.NoMatch){ reset() }

        previousState = nextState

        return nextState
    }

    private fun reset() {
        currentMatchColumn = -1
        activeRows.set(0, delimeterMatrix.size)
    }

    private fun moveNext(next: Char) {
        currentMatchColumn += 1

        activeRows.removeIf { activeRowIndex ->
            val row = delimeterMatrix[activeRowIndex]
            row.size == currentMatchColumn || row[currentMatchColumn] != next
        }
    }
}

private inline fun BitSet.removeIf(predicate: (Int) -> Boolean){
    var currentSetIndex = 0
    while(true) {
        currentSetIndex = this.nextSetBit(currentSetIndex)
        if(currentSetIndex == -1) break;

        if (predicate(currentSetIndex)) {
            clear(currentSetIndex)
        }
        currentSetIndex += 1
    }
}

enum class State { NoMatch, NewMatch, ContinuedMatch }

internal fun <T> ReceiveChannel<T>.tail(bufferSize: Int, context: CoroutineContext = Unconfined): ReceiveChannel<T> {

    // see [ProcessBuilder.standardErrorBufferCharCount]
    val channelTypeOrArrayBufferSize = bufferSize.asQueueChannelCapacity()

    //if we request a buffer size of 0, we use a simple conflated channel.
    val channelActual = Channel<T>(channelTypeOrArrayBufferSize)

    val buffer = object: Channel<T> by channelActual {
        override fun toString() = "tail[${bufferSize.toByteSizeString()}]-${this@tail}"
    }

    trace { "allocated buffer=$bufferSize (${bufferSize.toByteSizeString()}) for $buffer" }

    GlobalScope.launch(context + CoroutineName(buffer.toString())) {
        try {
            this@tail.consumeEach { nextChar ->
                if (bufferSize != 0) {
                    buffer.pushForward(nextChar)
                }
            }
        }
        finally {
            buffer.close()
        }
    }

    return buffer
}

private fun Int.toByteSizeString(): String = when(this){
    in 0 until 1024 ->  "${this}b"
    in 1024 until 1024*1024 ->  "${this/1024}kb"
    in 1024*1024 until Int.MAX_VALUE ->  "${this/(1024*1024)}mb"
    else -> TODO()
}

internal fun Int.asQueueChannelCapacity(): Int = when (this) {
    0 -> Channel.CONFLATED //TODO: a custom 'EmptyCloseableChannel' impl here would be neat
//    1 -> Channel.CONFLATED // when you close a conflated channel, it treats that as a token, throwing out the last element
    in 1 until Int.MAX_VALUE -> this
    Int.MAX_VALUE -> Channel.UNLIMITED
    else -> TODO("cant allocate buffer for size=${this}")
}

internal suspend inline fun <T> Channel<T>.pushForward(next: T): List<T> {
    var dropped: List<T> = emptyList()
    while (!offer(next) && ! isClosedForSend) {
        val bumpedElement = receiveOrNull() ?: continue
        dropped += bumpedElement
    }
    return dropped.also { if(dropped.any()) {
        trace { "WARN: back-pressure from $this dropped:" + dropped.joinToString("\n\t", "\n\t") }
    }}
}

internal fun OutputStream.toSendChannel(config: ProcessBuilder): SendChannel<Char> {
    return GlobalScope.actor<Char>(Unconfined + CoroutineName("process.stdin")) {

        val writer = OutputStreamWriter(this@toSendChannel, config.encoding)

        var sawClosedException = false
        try {
            consumeEach { nextChar ->

                try {
                    writer.append(nextChar)
                    if (nextChar == config.inputFlushMarker) writer.flush()
                }
                catch (ex: IOException) {
                    //writer was closed, process was terminated.
                    //TODO need a test to induce this, verify correctness.
                    trace { "WARN: failed to write nextchar='$nextChar' to stdin" }
                    sawClosedException = true
                    return@actor
                }
            }
        }
        finally {
            // if you write to a writer that is backed by a closed stream,
            // the writer appears to close itself in turn.
            if ( ! sawClosedException){
                writer.close()
            }
        }
    }
}

internal fun <T> SendChannel<T>.lockedBy(
        mutex: Mutex,
        context: CoroutineContext = Unconfined
): SendChannel<T> = GlobalScope.actor(context, onCompletion = { ex -> close(ex) }) {
    consumeEach {
        mutex.withLock {
            send(it)
        }
    }
}

// TODO what about exceptions?

internal fun <R, T> SendChannel<R>.flatMap(
        context: CoroutineContext = Unconfined,
        transform: (T) -> Iterable<R>
): SendChannel<T> = GlobalScope.actor(context, onCompletion = { ex -> close(ex) }) {
    consumeEach {
        val nextBatch = transform(it)
        for(element in nextBatch){
            send(element)
        }
    }
}

@InternalCoroutinesApi
internal fun <T> ReceiveChannel<T>.sinkTo(
        destination: SendChannel<T>,
        context: CoroutineContext = Unconfined
): Unit {
    GlobalScope.launch(context) {
        consumeEach {
            destination.send(it)
        }
    }.invokeOnCompletion(onCancelling = true) { ex -> destination.close(ex) }
}

@InternalCoroutinesApi
internal fun <T> SendChannel<T>.sinkFrom(
        source: ReceiveChannel<T>,
        context: CoroutineContext = Unconfined
): Unit {
    GlobalScope.launch(context) {
        source.consumeEach {
            send(it)
        }
    }.invokeOnCompletion(onCancelling = true) { ex -> close(ex) }
}

internal fun <T> CompletableDeferred<T>.sinkFrom(
        source: Deferred<T>,
        context: CoroutineContext = Unconfined
): Unit {
    GlobalScope.launch(context) {
        try {
            val result = source.await()
            complete(result)
        }
        catch(ex: Exception){
            completeExceptionally(ex)
        }
    }
}
