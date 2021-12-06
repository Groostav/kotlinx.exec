package groostav.kotlinx.exec

import org.junit.Test
import kotlin.test.assertEquals

class CircularArrayFixture {

    @Test fun `when adding elements should work properly`(){
        val array = CircularArray<String>(5)

        array += "0"
        array += "1"
        array += "2"
        array += "3"

        assertEquals(4, array.size)
        assertEquals(listOf("0", "1", "2", "3"), array)
    }

    @Test fun `when adding to the point where the array circulates should work properly`(){
        val array = CircularArray<String>(5)

        array += "0"
        array += "1"
        array += "2"
        array += "3"
        array += "4"
        array += "5"
        array += "6"
        array += "7"

        assertEquals(listOf("3", "4", "5", "6", "7"), array)
    }
}