# kotlinx.exec
suspending Kotlin façade for processes and process IO.

```kt
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channel.*
import kotlinx.exec.*

launch {
    //simple + windows 
    val calcExitCode: Int = exec("calc.exe")
    
    //complex + linux 
    val outputs: ReceiveChannel<String> = execAsync { command = listOf("ps", "-l") }.map { output -> 
        when (output) {
            is StandardError -> "error: ${output.line}"
            is StandardOutput -> output.line
            is ExitCode -> "(returned exit code ${output.code})"
        }
    }
}
```

More examples in [WindowsTests.kt](https://github.com/Groostav/kotlinx.exec/blob/master/src/test/kotlin/groostav/kotlinx/exec/WindowsTests.kt)

# Documentation #
_heh_

# Downloading #
_TBD_, currently bintray: https://bintray.com/groostav/generic/kotlinx.exec
