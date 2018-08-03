package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch

internal class SharedPollingResult(private val process: Process): ProcessControlFacade{

    companion object {
        val pollPeriodMillis = Integer.getInteger("groostav.kotlinx.exec.pollPeriodMillis") ?: 300
    }

    override val completionEvent: Maybe<ResultEventSource>
        get() = Supported { handler ->
            launch(blockableThread) {

                while (process.isAlive) {
                    delay(pollPeriodMillis)
                }

                handler.invoke(process.exitValue())
            }
        }
}