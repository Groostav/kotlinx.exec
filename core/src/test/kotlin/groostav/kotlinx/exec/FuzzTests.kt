package groostav.kotlinx.exec

import org.junit.Test

class FuzzTests{
    @Test fun todo(): Unit = TODO("""
        we should use some fuzzing framework to hit our job object, see if we can blow up my states.
    """.trimIndent())


    @Test fun `when calling cancel at random intervals on exec coroutine should properly cleanup process`(){
        TODO()
    }
}