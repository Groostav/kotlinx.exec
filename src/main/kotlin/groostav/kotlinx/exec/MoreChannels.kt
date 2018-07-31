package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.launch

//TODO: why isn't this part of kotlinx.coroutines already? Something they know I dont?
internal fun ReceiveChannel<Char>.lines(
        delimiters: List<String> = listOf("\r", "\n", "\r\n")
): ReceiveChannel<String> = produce<String>(Unconfined){

    trace { "starting 'lines' on ${this@lines}" }

    val buffer = StringBuilder(80)
    fun StringBuilder.takeAndClear(): String = toString().also { buffer.setLength(0) }

    val stateMachine = StateMachine(delimiters)

    this@lines.consumeEach { nextChar ->

        val newState = stateMachine.translate(nextChar)

        when(newState){
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

    trace { "No more lines on ${this@lines}" }
}

private class StateMachine(delimiters: List<String>) {
    val delimeterMatrix: Array<CharArray> = delimiters.map { it.toCharArray() }.toTypedArray()
    var currentMatchColumn: Int = -1
    val activeRows: MutableSet<Int> = (0 until delimeterMatrix.size).toHashSet()

    var previousState: State = State.NoMatch

    fun translate(next: Char): State {

        currentMatchColumn += 1

        activeRows.removeIf { activeRowIndex ->
            val row = delimeterMatrix[activeRowIndex]
            row.size == currentMatchColumn || row[currentMatchColumn] != next
        }

        val nextState = when (previousState) {
            State.NoMatch -> {
                if (activeRows.any()) State.NewMatch else State.NoMatch
            }
            State.NewMatch -> {
                if (activeRows.any()) State.ContinuedMatch else State.NoMatch
            }
            State.ContinuedMatch -> {
                if (activeRows.any()) State.ContinuedMatch else State.NoMatch
            }
        }

        if(activeRows.isEmpty()){
            currentMatchColumn = -1
            activeRows.run { addAll(0 until delimeterMatrix.size) }
        }
        previousState = nextState

        return nextState
    }
}

enum class State { NoMatch, NewMatch, ContinuedMatch }


internal fun <T> ReceiveChannel<T>.backPressureFreeMostRecent(bufferSize: Int): Channel<T> {

    val buffer = ArrayChannel<T>(bufferSize) //TODO CharArrayChannel?
    launch(Unconfined) {
        try {
            for (item in this@backPressureFreeMostRecent) {
                buffer.pushBack(item)
            }
        }
        finally {
            buffer.close()
        }
    }

    return buffer
}

private inline suspend fun <T> ArrayChannel<T>.pushBack(next: T){
    try {
        while (!offer(next)) {
            receiveOrNull()
        }
    }
    catch(ex: ClosedSendChannelException){
        // means we cant take anymore ever, so throw for cancellation of parent
        throw ex
    }
    catch(ex: ClosedReceiveChannelException){
        // ignore, this means we couldnt drop the element and wont be able to make space,
        // but space might still open up.
    }
}