package groostav.kotlinx.exec

import assertThrows
import emptyScriptCommand
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class UnhappyConfigurationTests {

    @Test fun `when attempting to run nonexistant program should get exception`() = runBlocking<Unit> {
        TODO("just discovered a java.io.IOException when running ps1 on ubuntu. should we wrap this?")
//        java.io.IOException: Cannot run program "powershell.exe" (in directory "/home/geoff/kotlinx.exec/."): error=2, No such file or directory
//
//        at java.lang.ProcessBuilder.start(ProcessBuilder.java:1048)
//        at groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:26)
//        at groostav.kotlinx.exec.ExecKt.execAsync$default(exec.kt:8)
//        at groostav.kotlinx.exec.ExecKt.execAsync(exec.kt:43)
//        at groostav.kotlinx.exec.JoinAwaitAndKillTests$when killing a process should exit without finishing$1.doResume(JoinAwaitAndKillTests.kt:19)
//        at kotlin.coroutines.experimental.jvm.internal.CoroutineImpl.resume(CoroutineImpl.kt:42)
//        at kotlinx.coroutines.experimental.DispatchedTask$DefaultImpls.run(Dispatched.kt:162)
//        at ...
//        at kotlinx.coroutines.experimental.BuildersKt.runBlocking$default(Unknown Source)
//        at groostav.kotlinx.exec.JoinAwaitAndKillTests.when killing a process should exit without finishing(JoinAwaitAndKillTests.kt:17)
//        at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
//        at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
//        at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
//        at java.lang.reflect.Method.invoke(Method.java:498)
//        at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:50)
//        at ...
//        at com.intellij.rt.execution.junit.JUnitStarter.main(JUnitStarter.java:70)
//        
//        Caused by: java.io.IOException: error=2, No such file or directory
//        at java.lang.UNIXProcess.forkAndExec(Native Method)
//        at java.lang.UNIXProcess.<init>(UNIXProcess.java:247)
//        at java.lang.ProcessImpl.start(ProcessImpl.java:134)
//        at java.lang.ProcessBuilder.start(ProcessBuilder.java:1029)
//        ... 36 more
    }

    @Test fun `when attempting to read from unbufferred channel should get exception`() = runBlocking<Unit> {
        //setup
        val runningProcess = execAsync {
            command = emptyScriptCommand()

            standardOutputBufferCharCount = 0
            standardErrorBufferCharCount = 0
            aggregateOutputBufferLineCount = 0
        }

        //act & assert
        assertThrows<IllegalStateException> { runningProcess.standardError }
        assertThrows<IllegalStateException> { runningProcess.standardError }

        //cleanup --done outside of finally block because above errors are more important
        runningProcess.join()
    }

    @Test fun `when attempting to get status of unbufferred channel should get good behaviour`() = runBlocking<Unit> {

        //setup
        val runningProcess = execAsync {
            command = emptyScriptCommand()
            aggregateOutputBufferLineCount = 0
        }

        //act
        val beforeClose = object {
            val isClosedForReceive = runningProcess.isClosedForReceive
            val isEmpty = runningProcess.isEmpty
            val poll = runningProcess.poll()
        }

        runningProcess.send("done!")
        val receivedElement = runningProcess.receiveOrNull()
        runningProcess.join()

        val afterClose = object {
            val isClosedForReceive = runningProcess.isClosedForReceive
            val isEmpty = runningProcess.isEmpty
            val poll = runningProcess.poll()
        }

        //act
        assertEquals(false, beforeClose.isClosedForReceive)
        assertEquals(true, beforeClose.isEmpty)
        assertEquals(null, beforeClose.poll)
        assertEquals(ExitCode(0), receivedElement)
        assertEquals(true, afterClose.isClosedForReceive)
        assertEquals(false, afterClose.isEmpty) //thats pretty weird, a closed channel is non-empty? huh.
        assertEquals(null, afterClose.poll)
    }

    @Test fun todo(){
        TODO("""
            hmm, so aggregate channel acts as a null-object when set to 0 buffer,
            but stdout char channel explodes when you get() it with the same config, seems odd and inconsistent.
        """.trimIndent())
    }
}