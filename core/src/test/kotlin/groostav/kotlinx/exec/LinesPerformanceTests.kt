package groostav.kotlinx.exec

import org.junit.Test

class LinesPerformanceTests {

    @Test fun todo(){
        TODO("implement a jmh or similar test to find out what the performance hit of an ArrayChannel<Char>.lines() " +
                "vs something like CharSequence.lines() is. Maybe create in-memory readers and bench the performance" +
                "of using Reader(inputStream).nextLine() vs Channel(inputStream).nextLine()"
        )
    }
}