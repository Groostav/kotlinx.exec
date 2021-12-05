package groostav.kotlinx.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.CharBuffer
import java.nio.charset.Charset

internal class NamedTracingProcessReader private constructor(
    val rawSrc: InputStream,
    val name: String,
    encoding: Charset
): Reader() {

    val src = InputStreamReader(rawSrc, encoding)

    init {
//        trace { "SOF on $this" }
    }

    suspend fun readAvailable(force: Boolean): CharArray? {

        //ok, we try really hard to be hands off, but its not going to work.
        // the `available()` check is good, it is best effort.
        // ready() seems more firm, but since EOF doesnt make the reader ready()==true,
        // we have this problem where after the process exits, we need to pump the stream until completion.
        // unfortunately child processes can hold a parents console open, thus holding its standard-output open.

        fun readMinimallyBlocking(): CharArray? {

            val available = rawSrc.available() // this value does return non-zero answers on windows
            val bufSize = available.coerceAtLeast(256)
            val cbuf = CharArray(bufSize)
            val readCount = read(cbuf)

            return when(readCount){
                EOF_VALUE -> null
                0 -> charArrayOf()
                cbuf.size -> cbuf
                else -> cbuf.sliceArray(0 until readCount)
            }
        }

        val ready = ready()

        return when {
            ready -> readMinimallyBlocking()
            force -> {
                // so: process.inputstream.read() is not interruptable.
                // so we have no mechanism to recover a read()...
                // so the best I can do here is check delay and recheck
                // if the stream is still open it means a child process has taken it,
                delay(500L)
                if(ready()) {
                    readMinimallyBlocking()
                }
                return END_OF_FILE_CHUNK
            }
            else -> charArrayOf()
        }
    }

    override fun skip(n: Long): Long = src.skip(n)
    override fun ready(): Boolean = src.ready()
    override fun reset() = src.reset()
    override fun close() = src.close()
    override fun markSupported(): Boolean = src.markSupported()
    override fun mark(readAheadLimit: Int) = src.mark(readAheadLimit)
    override fun read(target: CharBuffer): Int = src.read(target)

    override fun read(): Int = src.read()//.also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }
    override fun read(cbuf: CharArray): Int = src.read(cbuf)//.also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }
    override fun read(cbuf: CharArray, off: Int, len: Int): Int = src.read(cbuf, off, len)//.also { if(it == EOF_VALUE){ trace { "EOF on $this" } } }

    override fun toString() = name

    companion object {

        fun forStandardError(process: Process, pid: Long, charset: Charset) =
            NamedTracingProcessReader(process.errorStream, "stderr-$pid", charset)

        fun forStandardOutput(process: Process, pid: Long, charset: Charset) =
            NamedTracingProcessReader(process.inputStream, "stdout-$pid", charset)
    }
}

private const val EOF_VALUE = -1