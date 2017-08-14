package kotlinx.coroutines.experimental.internal

/**
 * Synchronized binary heap.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
impl public class ThreadSafeHeap<T> where T : ThreadSafeHeapNode, T : Comparable<T> {
    private var a: Array<T?>? = null

    @PublishedApi
    impl internal var size = 0

    impl public val isEmpty: Boolean get() = size == 0

    impl public fun peek(): T? = firstImpl()

    impl public fun removeFirstOrNull(): T? =
            if (size > 0) {
                removeAtImpl(0)
            } else null

    impl public inline fun removeFirstIf(predicate: (T) -> Boolean): T? {
        val first = firstImpl() ?: return null
        return if (predicate(first)) {
            removeAtImpl(0)
        } else null
    }

    impl public fun addLast(node: T) = addImpl(node)

    impl public fun addLastIf(node: T, cond: () -> Boolean): Boolean =
            if (cond()) {
                addImpl(node)
                true
            } else false

    impl public fun remove(node: T): Boolean =
            if (node.index < 0) {
                false
            } else {
                removeAtImpl(node.index)
                true
            }

    @PublishedApi
    impl internal fun firstImpl(): T? = a?.get(0)

    @PublishedApi
    impl internal fun removeAtImpl(index: Int): T {
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
    impl internal fun addImpl(node: T) {
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