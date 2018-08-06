package groostav.kotlinx.exec

import org.junit.Test
import kotlin.test.assertEquals

class GetIntRangeTests {

    @Test fun `when parsing simple it range should get correct result`(){
        //setup
        val key = "groostav.testing.abcd"
        System.setProperty(key, "1..234")

        //act
        val result = getIntRange(key)

        //assert
        assertEquals(1..234, result)
    }

    @Test fun `when parsing ugly negative range should get correct result`(){
        //setup
        val key = "groostav.testing.abcd"
        System.setProperty(key, "-5..-0")

        //act
        val result = getIntRange(key)

        //assert
        assertEquals(-5..0, result)
    }
}