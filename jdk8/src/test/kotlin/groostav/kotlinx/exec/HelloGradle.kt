package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test

class HelloGradle {

    @Test fun `another`() = runBlocking {
        val (a, r) = exec {
            command = listOf("cmd.exe", "/C", "echo", "testing")
        }
    }
}