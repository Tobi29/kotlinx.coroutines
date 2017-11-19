package kotlinx.coroutines.experimental.internal

/**
 * Descriptor for multi-word atomic operation.
 * Based on paper
 * ["A Practical Multi-Word Compare-and-Swap Operation"](http://www.cl.cam.ac.uk/research/srg/netos/papers/2002-casn.pdf)
 * by Timothy L. Harris, Keir Fraser and Ian A. Pratt.
 *
 * Note: parts of atomic operation must be globally ordered. Otherwise, this implementation will produce
 * [StackOverflowError].
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
public expect abstract class AtomicOp<in T> : OpDescriptor {
    val isDecided: Boolean

    fun tryDecide(decision: Any?): Boolean

    abstract fun prepare(affected: T): Any? // `null` if Ok, or failure reason

    abstract fun complete(affected: T, failure: Any?) // failure != null if failed to prepare op

    // returns `null` on success
    @Suppress("UNCHECKED_CAST")
    final override fun perform(affected: Any?): Any?
}
