package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test

class HelloGradle {

    @Test fun `when things then things`(): Unit = TODO("on build path!")

    @Test fun `another`() = runBlocking {
        val (a, r) = exec {

        }
    }
}