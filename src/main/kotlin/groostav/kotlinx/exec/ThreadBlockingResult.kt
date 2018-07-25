package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.launch
import java.util.concurrent.atomic.AtomicReference

internal class ThreadBlockingResult(val jvmProcess: java.lang.Process): ProcessFacade {

    private val handlers: AtomicReference<State> = AtomicReference(Uninitialized)

    override fun addCompletionHandle() = Supported { handler: ResultHandler ->

        val previousState = handlers.getAndUpdate { state ->
            when(state) {
                Uninitialized -> Waiting(listOf(handler))
                is Waiting -> Waiting(state.handlers + handler) //would really really like kotlinx.immutable.collections :(
                is Finished -> state
            }
        }

        when(previousState){
            Uninitialized -> {
                // we're the first to request a result value, so we need to initialize the blocking thread
                launch(blockableThread) {
                    val result = jvmProcess.waitFor()
                    fireCompletion(result)
                }
            }
            is Waiting -> {
                // noop, our handler is registered, we dont need to do any cleanup.
            }
            is Finished -> {
                // already done, so just fire the handler right now
                handler(previousState.result)
            }
        }

        Unit
    }

    private fun fireCompletion(result: Int){
        val previousState = handlers.getAndUpdate { state ->
            when(state){
                Uninitialized -> throw IllegalStateException()
                is Waiting -> Finished(result)
                is Finished -> state
            }
        }

        if(previousState is Waiting){
            previousState.handlers.forEach { handler -> handler.invoke(result) }
        }
    }
}

private sealed class State
private object Uninitialized: State()
private class Waiting(val handlers: List<ResultHandler>): State()
private class Finished(val result: Int): State()