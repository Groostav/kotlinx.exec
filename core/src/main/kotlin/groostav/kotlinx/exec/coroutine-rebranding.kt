package groostav.kotlinx.exec

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.resumeCancellableWith
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.createCoroutine

// these functions exist because I dont like some of elizarovs names
// --or at least, those names dont match the concepts in my head,
// so I'm renaming them here

// closed in the sense of a 'closure',
// it has no inputs because they are assigned via other means
// (typically fields/ctor-args on an AbstractCoroutine state machine)
// note that a Continuation is a function<T>
// (in this case a function(Unit), IE a void-void function)
// and, importantly, a coroutineContext. The "closedReadyCoroutine"
// doesnt actually need to be much of a lambda;
// it is a closure, and it is the result of the compiler's state-machine generation,
// but for the caller of this function the only important exposed information
// is that it has a coroutineContext.
// --maybe this is better semantically described by actually returning a
// CoroutineContext with a [Runnable] as a key-value pair?
typealias ClosedReadyCoroutine = Continuation<Unit>

@InternalCoroutinesApi
internal fun <T> makeCoroutine(
    suspensionGappedFunction: suspend () -> T,
    context: CoroutineContext,
    pipeOutputTo: (T) -> Unit
): ClosedReadyCoroutine {
    return suspensionGappedFunction.createCoroutine(object: Continuation<T>{
        // TBD: does this break coroutines internal 'SafeContinuation' checks?
        // is kotlin coroutines ever doing an 'if(cont is SafeContinuation)'?
        // also: does this break lazyness of context retrieval?
        // does any AbstractCoroutine implementation not provide a constant value for its CoroutineContext?
        // if yes, they we are effectively eagerly retrieving it and we may have a stale cache...
        override val context get() = context
        override fun resumeWith(result: Result<T>) {
            // ahh haaa... so, this is another api oddity:
            // the createCoroutine function will never actually call us with a Result.failed;
            // because the API reasonably splits ~eagerly generated 'ClosedReadyCoroutine'
            // instances from RuntimeExceptions,
            // --in other words, the only way to invoke a ClosedReadyCoroutine is to provide
            // a finally block, either as a `try { closedReadyCoroutine.invoke() } catch { ... }`
            // or through `closedReadyCoroutine.tryInvoke(finally = ...)`
            when {
                result.isSuccess -> pipeOutputTo.invoke(result.getOrNull()!!)
                result.isFailure -> {
                    TODO("internal error: attempted to resume a piped-output with an exception")
                }
            }
        }
    })
}

@InternalCoroutinesApi
internal fun ClosedReadyCoroutine.tryCancellablyInvoke(finally: (Throwable) -> Unit){
    try {
        // so, despite the fact that kotlin-coroutines uses continuations here...
        // im not actually sure what the pipe-ing of the CoroutineContext is?
        this.resumeCancellableWith(Result.success(Unit))
    }
    catch(ex: Throwable){
        finally.invoke(ex)
    }
}