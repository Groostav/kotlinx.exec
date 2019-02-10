package groostav.kotlinx.exec

import io.mockk.mockk
import org.junit.Test
import java.lang.UnsupportedOperationException
import kotlin.test.assertEquals

class MaybeFixture {

    internal object UselessProcessControl: ProcessControlFacade, ProcessControlFacade.Factory {

        val NOT_SUPPORTED_BECAUSE_REASONS = Unsupported("reasons.")

        override fun tryKillGracefullyAsync(includeDescendants: Boolean) = NOT_SUPPORTED_BECAUSE_REASONS
        override fun killForcefullyAsync(includeDescendants: Boolean) = NOT_SUPPORTED_BECAUSE_REASONS

        override fun create(config: ProcessConfiguration, process: Process, pid: Int): Maybe<ProcessControlFacade> {
            return Supported(UselessProcessControl)
        }
    }

    internal object NotWindowsProcessControlFactory: ProcessControlFacade.Factory {
        val OS_IS_NO_GOOD = Unsupported("OS isnt quite right.")
        override fun create(config: ProcessConfiguration, process: Process, pid: Int) = OS_IS_NO_GOOD
    }

    @Test
    fun `when nothing works but with different levels of support should produce nice error`(): Unit {

        val composite = CompositeProcessControlFactory(listOf(
                UselessProcessControl,
                NotWindowsProcessControlFactory
        ))

        //act
        val process = mockk<Process>()
        val instance = composite.create(ProcessConfiguration(), process, 42)
        val ex= Catch<UnsupportedOperationException> { instance.value.killForcefullyAsync(true) }

        //assert
        assertEquals("""
                killForcefullyAsync is not supported:
                    MaybeFixture.UselessProcessControl/killForcefullyAsync: reasons.
                    ProcessControlFacade/killForcefullyAsync:
                        MaybeFixture.NotWindowsProcessControlFactory/create: OS isnt quite right.
        """.trimIndent(), ex?.message?.replace("\t", "    "))
    }

    internal fun unrelated(asdf: ProcessControlFacade.Factory): Maybe<ProcessControlFacade.Factory> = Unsupported("because we're testing!")

    @Test
    fun `when nothing works but with three levels of support should produce nice error`(): Unit {

        val instance = Supported(NotWindowsProcessControlFactory)
                .supporting(this::unrelated)
                .supporting(ProcessControlFacade.Factory::create, ProcessConfiguration(), mockk(), 42)
                .supporting(ProcessControlFacade::killForcefullyAsync, true)

        //act
        val ex= Catch<UnsupportedOperationException> { instance.value }

        //assert
        assertEquals("""
                cannot invoke ProcessControlFacade/killForcefullyAsync:
                    ProcessControlFacade.Factory/create:
                    MaybeFixture.NotWindowsProcessControlFactory/unrelated: because we're testing!
        """.trimIndent(), ex?.message?.replace("\t", "    "))
    }
}

