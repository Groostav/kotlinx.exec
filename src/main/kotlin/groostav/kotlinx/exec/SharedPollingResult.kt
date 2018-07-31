package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.CopyOnWriteArrayList

internal class SharedPollingResult(private val process: Process): ProcessFacade{

    companion object {
        val pollPeriodMillis = Integer.getInteger("groostav.kotlinx.exec.pollPeriodMillis") ?: 300
    }

    override fun addCompletionHandle(): Maybe<ResultEventSource> = Supported { handler ->
        launch(blockableThread) {

            while(process.isAlive){
                delay(pollPeriodMillis)
            }

            handler.invoke(process.exitValue())
        }
    }
}