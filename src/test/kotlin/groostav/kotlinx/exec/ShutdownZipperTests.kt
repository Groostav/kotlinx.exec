package groostav.kotlinx.exec

import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class ShutdownZipperTests {

    private enum class ShutdownThing { First, Second, Third }

    @Test fun `when using shutdown zipper should properly execute`() = runBlocking {

        val singleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val zipper = ShutdownZipper(ShutdownThing.values().asList())
        var results: List<String> = emptyList()

        //act
        val secondWaiting = launch(singleThread) {
            zipper.waitFor(ShutdownThing.Second);
            results += "second"
        }
        val firstWaiting = launch(singleThread) {
            zipper.waitFor(ShutdownThing.First);
            results += "first"
        }
        val thirdWaiting = launch(singleThread) {
            zipper.waitFor(ShutdownThing.Third);
            results += "third"
        }

        thirdWaiting.join()
        secondWaiting.join()
        firstWaiting.join()

        //assert
        assertEquals(listOf("first", "second", "third"), results)
    }

    private enum class AnotherShutdownThing { Once, Twice, Thrice, FourTimes, FiveTimes, SixTimes, SevenTimes }
    @Test fun `when using shutdown zipper more elaborately should properly execute`() = runBlocking {

        val singleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val zipper = ShutdownZipper(AnotherShutdownThing.values().asList())
        var results: List<String> = emptyList()

        //act
        listOf(
                launch(singleThread) { zipper.waitFor(AnotherShutdownThing.Twice); results += "Twice" },
                launch(singleThread) { zipper.waitFor(AnotherShutdownThing.FourTimes); results += "FourTimes" },
                launch(singleThread) { zipper.waitFor(AnotherShutdownThing.Once); results += "Once" },
                launch(singleThread) { zipper.waitFor(AnotherShutdownThing.FiveTimes); results += "FiveTimes" },
                launch(singleThread) { zipper.waitFor(AnotherShutdownThing.SixTimes); results += "SixTimes" },
                launch(singleThread) { zipper.waitFor(AnotherShutdownThing.Thrice); results += "Thrice" },
                launch(singleThread) { zipper.waitFor(AnotherShutdownThing.SevenTimes); results += "SevenTimes" }
        ).forEach { it.join() }

        //assert
        val expected = listOf(
                "Once",
                "Twice",
                "Thrice",
                "FourTimes",
                "FiveTimes",
                "SixTimes",
                "SevenTimes"
        )
        assertEquals(expected, results)
    }
}
