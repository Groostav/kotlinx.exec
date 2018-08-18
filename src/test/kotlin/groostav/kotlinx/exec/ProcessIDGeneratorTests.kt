package groostav.kotlinx.exec

import org.junit.Test

class ProcessIDGeneratorTests {

    @Test fun `when attempting to get PID for dead process should succeed`(){
        TODO(
                "install some kind of blocking utility that blocks the start-process call until the process is already dead, " +
                        "then let the PID generators get its PID, they should still succeed."
        )
    }
}