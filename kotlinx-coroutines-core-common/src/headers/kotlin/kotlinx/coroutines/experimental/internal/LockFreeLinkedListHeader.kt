package kotlinx.coroutines.experimental.internal

/**
 * Doubly-linked concurrent list node with remove support.
 * Based on paper
 * ["Lock-Free and Practical Doubly Linked List-Based Deques Using Single-Word Compare-and-Swap"](http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.140.4693&rep=rep1&type=pdf)
 * by Sundell and Tsigas.
 *
 * Important notes:
 * * The instance of this class serves both as list head/tail sentinel and as the list item.
 *   Sentinel node should be never removed.
 * * There are no operations to add items to left side of the list, only to the end (right side), because we cannot
 *   efficiently linearize them with atomic multi-step head-removal operations. In short,
 *   support for [describeRemoveFirst] operation precludes ability to add items at the beginning.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
@Suppress("LeakingThis")
header public open class LockFreeLinkedListNode {
    @PublishedApi
    header internal abstract class CondAddOp(
            newNode: Node
    ) : AtomicOp<Node> {
        val newNode: Node

        var oldNext: Node?

        override fun complete(affected: Node, failure: Any?)
    }

    @PublishedApi
    internal inline fun makeCondAddOp(node: Node, crossinline condition: () -> Boolean): CondAddOp

    public val isFresh: Boolean

    public val isRemoved: Boolean

    // LINEARIZABLE. Returns Node | Removed
    public val next: Any

    // LINEARIZABLE. Returns Node | Removed
    public val prev: Any

    // ------ addOneIfEmpty ------

    public fun addOneIfEmpty(node: Node): Boolean

    // ------ addLastXXX ------

    /**
     * Adds last item to this list.
     */
    public fun addLast(node: Node)

    public fun <T : Node> describeAddLast(node: T): AddLastDesc<T>

    /**
     * Adds last item to this list atomically if the [condition] is true.
     */
    public inline fun addLastIf(node: Node, crossinline condition: () -> Boolean): Boolean

    public inline fun addLastIfPrev(node: Node, predicate: (Node) -> Boolean): Boolean

    public inline fun addLastIfPrevAndIf(
            node: Node,
            predicate: (Node) -> Boolean, // prev node predicate
            crossinline condition: () -> Boolean // atomically checked condition
    ): Boolean

    // ------ addXXX util ------

    @PublishedApi
    internal fun addNext(node: Node, next: Node): Boolean

    // returns UNDECIDED, SUCCESS or FAILURE
    @PublishedApi
    internal fun tryCondAddNext(node: Node, next: Node, condAdd: CondAddOp): Int

    // ------ removeXXX ------

    /**
     * Removes this node from the list. Returns `true` when removed successfully, or `false` if the node was already
     * removed or if it was not added to any list in the first place.
     */
    public open fun remove(): Boolean

    public open fun describeRemove() : AtomicDesc?

    public fun removeFirstOrNull(): Node?

    public fun describeRemoveFirst(): RemoveFirstDesc<Node>

    public inline fun <reified T> removeFirstIfIsInstanceOf(): T?

    // just peek at item when predicate is true
    public inline fun <reified T> removeFirstIfIsInstanceOfOrPeekIf(predicate: (T) -> Boolean): T?

    // ------ multi-word atomic operations helpers ------

    header public open class AddLastDesc<out T : Node>(
            queue: Node,
            node: T
    ) : AbstractAtomicDesc() {
        val queue: Node
        val node: T

        final override fun takeAffectedNode(op: OpDescriptor): Node

        final override var affectedNode: Node?
        final override val originalNext: Node?

        override fun retry(affected: Node, next: Any): Boolean

        override fun onPrepare(affected: Node, next: Node): Any?

        override fun updatedNext(affected: Node, next: Node): Any

        override fun finishOnSuccess(affected: Node, next: Node)
    }

    header public open class RemoveFirstDesc<T>(
            queue: Node
    ) : AbstractAtomicDesc() {
        val queue: Node

        public val result: T

        final override fun takeAffectedNode(op: OpDescriptor): Node
        final override var affectedNode: Node?
        final override var originalNext: Node?

        // check node predicates here, must signal failure if affect is not of type T
        protected override fun failure(affected: Node, next: Any): Any?

        // validate the resulting node (return false if it should be deleted)
        protected open fun validatePrepared(node: T): Boolean

        final override fun retry(affected: Node, next: Any): Boolean

        @Suppress("UNCHECKED_CAST")
        final override fun onPrepare(affected: Node, next: Node): Any?

        final override fun updatedNext(affected: Node, next: Node): Any
        final override fun finishOnSuccess(affected: Node, next: Node)
    }

    header public abstract class AbstractAtomicDesc : AtomicDesc() {
        protected abstract val affectedNode: Node?
        protected abstract val originalNext: Node?
        protected open fun takeAffectedNode(op: OpDescriptor): Node
        protected open fun failure(affected: Node, next: Any): Any?
        protected open fun retry(affected: Node, next: Any): Boolean
        protected abstract fun onPrepare(affected: Node, next: Node): Any? // non-null on failure
        protected abstract fun updatedNext(affected: Node, next: Node): Any
        protected abstract fun finishOnSuccess(affected: Node, next: Node)

        final override fun prepare(op: AtomicOp<*>): Any?

        final override fun complete(op: AtomicOp<*>, failure: Any?)
    }

    // fixes next links to the left of this node
    @PublishedApi
    internal fun helpDelete()

    internal fun validateNode(prev: Node, next: Node)
}
