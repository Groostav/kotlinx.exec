package groostav.kotlinx.exec

import org.junit.Assert.assertEquals
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.channels.toList
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test


class LineParsingTests {

    @Test fun `when parsing two simple lines should properly group lines`() = runBlocking<Unit>{
        val chars = produce {
            send('a')
            send('b')
            send('c')
            send('\n')
            send('x')
            send('y')
            send('z')
        }

        //act
        val result = chars.lines(listOf("\n")).toList()

        //assert
        assertEquals(listOf("abc", "xyz"), result)
    }

    @Test fun `when parsing two lines with two newlines should properly group lines`() = runBlocking<Unit>{
        val chars = produce {
            send('a')
            send('b')
            send('c')
            send('\n')
            send('\n')
            send('x')
            send('y')
            send('z')
        }

        //act
        val result = chars.lines(listOf("\n")).toList()

        //assert
        assertEquals(listOf("abc", "", "xyz"), result)
    }

    @Test fun `when parsing lines separated by windows style newlines should properly group`() = runBlocking<Unit>{
        val chars = produce {
            send('a')
            send('b')
            send('c')
            send('\r')
            send('\n')
            send('x')
            send('y')
            send('z')
        }

        //act
        val result = chars.lines(listOf("\r", "\r\n", "\n")).toList()

        //assert
        assertEquals(listOf("abc", "xyz"), result)
    }


    @Test fun `when parsing lines separated by custom delim should properly group`() = runBlocking<Unit>{
        val chars = produce {
            send('a')
            send('b')
            send('c')
            send('x')
            send('x')
            send('x')
            send('1')
            send('2')
            send('3')
        }

        //act
        val result = chars.lines(listOf("xxx")).toList()

        //assert
        assertEquals(listOf("abc", "123"), result)
    }
}