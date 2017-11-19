package kotlinx.coroutines.experimental.internal

/**
 * Synchronized binary heap.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
public actual class ThreadSafeHeap<T> where T : ThreadSafeHeapNode, T : Comparable<T> {
    private var a: Array<T?>? = null

    @PublishedApi
    internal actual var size = 0

    public actual val isEmpty: Boolean get() = size == 0

    public actual fun peek(): T? = firstImpl()

    public actual fun removeFirstOrNull(): T? =
            if (size > 0) {
                removeAtImpl(0)
            } else null

    public actual inline fun removeFirstIf(predicate: (T) -> Boolean): T? {
        val first = firstImpl() ?: return null
        return if (predicate(first)) {
            removeAtImpl(0)
        } else null
    }

    public actual fun addLast(node: T) = addImpl(node)

    public actual fun addLastIf(node: T, cond: () -> Boolean): Boolean =
            if (cond()) {
                addImpl(node)
                true
            } else false

    public actual fun remove(node: T): Boolean =
            if (node.index < 0) {
                false
            } else {
                removeAtImpl(node.index)
                true
            }

    @PublishedApi
    internal actual fun firstImpl(): T? = a?.get(0)

    @PublishedApi
    internal actual fun removeAtImpl(index: Int): T {
        check(size > 0)
        val a = this.a!!
        size--
        if (index < size) {
            swap(index, size)
            var i = index
            while (true) {
                var j = 2 * i + 1
                if (j >= size) break
                if (j + 1 < size && a[j + 1]!! < a[j]!!) j++
                if (a[i]!! <= a[j]!!) break
                swap(i, j)
                i = j
            }
        }
        val result = a[size]!!
        result.index = -1
        a[size] = null
        return result
    }

    @PublishedApi
    internal actual fun addImpl(node: T) {
        val a = realloc()
        var i = size++
        a[i] = node
        node.index = i
        while (i > 0) {
            val j = (i - 1) / 2
            if (a[j]!! <= a[i]!!) break
            swap(i, j)
            i = j
        }
    }


    @Suppress("UNCHECKED_CAST")
    private fun realloc(): Array<T?> {
        val a = this.a
        return when {
            a == null -> (arrayOfNulls<ThreadSafeHeapNode>(4) as Array<T?>).also { this.a = it }
            size >= a.size -> a.copyOf(size * 2).also { this.a = it }
            else -> a
        }
    }

    private fun swap(i: Int, j: Int) {
        val a = a!!
        val ni = a[j]!!
        val nj = a[i]!!
        a[i] = ni
        a[j] = nj
        ni.index = i
        nj.index = j
    }
}