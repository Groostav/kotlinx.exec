package groostav.kotlinx.exec

import java.lang.StringBuilder

class OutputParser(delimiters: List<String>){

    private val buffer = StringBuilder()

    private val delimiters = delimiters.sortedByDescending { it.length }.toTypedArray()

    fun addAndParse(text: CharArray): List<String> {
        buffer.append(text)

        val lines = ArrayList<String>()

        do {
            var matchIndex: Int = -1
            var matchDelim: String? = null

            searchBuffer@ for (charIndex in buffer.indices) {
                for (delimeter in delimiters) {
                    if (buffer.matchesAt(charIndex, delimeter)) {
                        matchIndex = charIndex
                        matchDelim = delimeter

                        break@searchBuffer
                    }
                }
            }

            if (matchIndex != -1) {
                lines += buffer.substring(0, matchIndex)
                buffer.delete(0, matchIndex + matchDelim!!.length)
            }
        }
        while(matchIndex != -1)

        return lines
    }
}

private fun StringBuilder.matchesAt(charIndex: Int, delimeter: String): Boolean {
    for (delimIndex in delimeter.indices) {
        if (this[charIndex + delimIndex] != delimeter[delimIndex]) {
            return false
        }
    }
    return true
}