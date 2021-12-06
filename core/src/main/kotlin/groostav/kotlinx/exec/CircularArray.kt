package groostav.kotlinx.exec

internal class CircularArray<T: Any?>(val capacity: Int): AbstractList<T>() {

    @Suppress("UNCHECKED_CAST")
    private var elements: Array<T> = arrayOfNulls<Any>(capacity) as Array<T>

    private var head = 0

    override var size: Int = 0
        private set

    override fun get(index: Int): T = elements[(index + head) % capacity]

    operator fun plusAssign(next: T) {
        elements[(head + size) % capacity] = next
        if(size < capacity) size += 1 else head += 1
    }
    operator fun plusAssign(elements: List<T>) {
        elements.takeLast(capacity).forEach { this += it }
    }
}