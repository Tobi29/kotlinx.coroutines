/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
impl public open class LockFreeLinkedListNode {
    private var _next: Any = this // Node | Removed | OpDescriptor
    private var _prev: Any = this // Node | Removed
    private var _removedRef: Removed? = null // lazily cached removed ref to this

    private fun removed(): Removed =
            _removedRef ?: Removed(this).also { _removedRef = it }

    @PublishedApi
    impl internal abstract class CondAddOp(
            impl val newNode: Node
    ) : AtomicOp<Node>() {
        impl var oldNext: Node? = null

        impl override fun complete(affected: Node, failure: Any?) {
            val success = failure == null
            val update = if (success) newNode else oldNext
            if (affected._next == this) {
                affected._next = update!!
                // only the thread the makes this update actually finishes add operation
                if (success) newNode.finishAdd(oldNext!!)
            }
        }
    }

    @PublishedApi
    impl internal inline fun makeCondAddOp(node: Node, crossinline condition: () -> Boolean): CondAddOp =
            object : CondAddOp(node) {
                override fun prepare(affected: Node): Any? = if (condition()) null else CONDITION_FALSE
            }

    impl public val isFresh: Boolean get() = _next === this

    impl public val isRemoved: Boolean get() = next is Removed

    // LINEARIZABLE. Returns Node | Removed
    impl public val next: Any
        get() {
            while (true) { // operation helper loop on _next
                val next = this._next
                if (next !is OpDescriptor) return next
                next.perform(this)
            }
        }

    // LINEARIZABLE. Returns Node | Removed
    impl public val prev: Any
        get() {
            while (true) { // insert helper loop on _prev
                val prev = this._prev
                if (prev is Removed) return prev
                prev as Node // otherwise, it can be only node otherwise
                if (prev.next === this) return prev
                correctPrev(prev, null)
            }
        }

    // ------ addOneIfEmpty ------

    impl public fun addOneIfEmpty(node: Node): Boolean {
        node._prev = this
        node._next = this
        while (true) {
            val next = next
            if (next !== this) return false // this is not an empty list!
            if (_next == this) {
                _next = node
                // added successfully (linearized add) -- fixup the list
                node.finishAdd(this)
                return true
            }
        }
    }

    // ------ addLastXXX ------

    /**
     * Adds last item to this list.
     */
    impl public fun addLast(node: Node) {
        while (true) { // lock-free loop on prev.next
            val prev = prev as Node // sentinel node is never removed, so prev is always defined
            if (prev.addNext(node, this)) return
        }
    }

    impl public fun <T : Node> describeAddLast(node: T): AddLastDesc<T> = AddLastDesc(this, node)

    /**
     * Adds last item to this list atomically if the [condition] is true.
     */
    impl public inline fun addLastIf(node: Node, crossinline condition: () -> Boolean): Boolean {
        val condAdd = makeCondAddOp(node, condition)
        while (true) { // lock-free loop on prev.next
            val prev = prev as Node // sentinel node is never removed, so prev is always defined
            when (prev.tryCondAddNext(node, this, condAdd)) {
                SUCCESS -> return true
                FAILURE -> return false
            }
        }
    }

    impl public inline fun addLastIfPrev(node: Node, predicate: (Node) -> Boolean): Boolean {
        while (true) { // lock-free loop on prev.next
            val prev = prev as Node // sentinel node is never removed, so prev is always defined
            if (!predicate(prev)) return false
            if (prev.addNext(node, this)) return true
        }
    }

    impl public inline fun addLastIfPrevAndIf(
            node: Node,
            predicate: (Node) -> Boolean, // prev node predicate
            crossinline condition: () -> Boolean // atomically checked condition
    ): Boolean {
        val condAdd = makeCondAddOp(node, condition)
        while (true) { // lock-free loop on prev.next
            val prev = prev as Node // sentinel node is never removed, so prev is always defined
            if (!predicate(prev)) return false
            when (prev.tryCondAddNext(node, this, condAdd)) {
                SUCCESS -> return true
                FAILURE -> return false
            }
        }
    }

    // ------ addXXX util ------

    @PublishedApi
    impl internal fun addNext(node: Node, next: Node): Boolean {
        node._prev = this
        node._next = next
        if (_next == next) {
            _next = node
        } else return false
        // added successfully (linearized add) -- fixup the list
        node.finishAdd(next)
        return true
    }

    // returns UNDECIDED, SUCCESS or FAILURE
    @PublishedApi
    impl internal fun tryCondAddNext(node: Node, next: Node, condAdd: CondAddOp): Int {
        node._prev = this
        node._next = next
        condAdd.oldNext = next
        if (_next == next) {
            _next = condAdd
        } else return UNDECIDED
        // added operation successfully (linearized) -- complete it & fixup the list
        return if (condAdd.perform(this) == null) SUCCESS else FAILURE
    }

    // ------ removeXXX ------

    /**
     * Removes this node from the list. Returns `true` when removed successfully, or `false` if the node was already
     * removed or if it was not added to any list in the first place.
     */
    impl public open fun remove(): Boolean {
        while (true) { // lock-free loop on next
            val next = this.next
            if (next is Removed) return false // was already removed -- don't try to help (original thread will take care)
            if (next === this) return false // was not even added
            val removed = (next as Node).removed()
            if (_next == next) {
                _next = removed
                // was removed successfully (linearized remove) -- fixup the list
                finishRemove(next)
                return true
            }
        }
    }

    impl public open fun describeRemove(): AtomicDesc? {
        if (isRemoved) return null // fast path if was already removed
        return object : AbstractAtomicDesc() {
            override val affectedNode: Node? get() = this@LockFreeLinkedListNode
            override var originalNext: Node? = null
            override fun failure(affected: Node, next: Any): Any? =
                    if (next is Removed) ALREADY_REMOVED else null

            override fun onPrepare(affected: Node, next: Node): Any? {
                originalNext = next
                return null // always success
            }

            override fun updatedNext(affected: Node, next: Node) = next.removed()
            override fun finishOnSuccess(affected: Node, next: Node) = finishRemove(next)
        }
    }

    impl public fun removeFirstOrNull(): Node? {
        while (true) { // try to linearize
            val first = next as Node
            if (first === this) return null
            if (first.remove()) return first
            first.helpDelete() // must help delete, or loose lock-freedom
        }
    }

    impl public fun describeRemoveFirst(): RemoveFirstDesc<Node> = RemoveFirstDesc(this)

    impl public inline fun <reified T> removeFirstIfIsInstanceOf(): T? {
        while (true) { // try to linearize
            val first = next as Node
            if (first === this) return null
            if (first !is T) return null
            if (first.remove()) return first
            first.helpDelete() // must help delete, or loose lock-freedom
        }
    }

    // just peek at item when predicate is true
    impl public inline fun <reified T> removeFirstIfIsInstanceOfOrPeekIf(predicate: (T) -> Boolean): T? {
        while (true) { // try to linearize
            val first = next as Node
            if (first === this) return null
            if (first !is T) return null
            if (predicate(first)) return first // just peek when predicate is true
            if (first.remove()) return first
            first.helpDelete() // must help delete, or loose lock-freedom
        }
    }

    // ------ multi-word atomic operations helpers ------

    impl public open class AddLastDesc<out T : Node>(
            impl val queue: Node,
            impl val node: T
    ) : AbstractAtomicDesc() {
        init {
            // require freshly allocated node here
            check(node._next === node && node._prev === node)
        }

        impl final override fun takeAffectedNode(op: OpDescriptor): Node {
            while (true) {
                val prev = queue._prev as Node // this sentinel node is never removed
                val next = prev._next
                if (next === queue) return prev // all is good -> linked properly
                if (next === op) return prev // all is good -> our operation descriptor is already there
                if (next is OpDescriptor) { // some other operation descriptor -> help & retry
                    next.perform(prev)
                    continue
                }
                // linked improperly -- help insert
                queue.correctPrev(prev, op)
            }
        }

        impl final override var affectedNode: Node? = null
        impl final override val originalNext: Node? get() = queue

        impl override fun retry(affected: Node, next: Any): Boolean = next !== queue

        impl override fun onPrepare(affected: Node, next: Node): Any? {
            affectedNode = affected
            return null // always success
        }

        impl override fun updatedNext(affected: Node, next: Node): Any {
            // it is invoked only on successfully completion of operation, but this invocation can be stale,
            // so we must use CAS to set both prev & next pointers
            if (node._prev == node) node._prev = affected
            if (node._next == node) node._next = queue
            return node
        }

        impl override fun finishOnSuccess(affected: Node, next: Node) {
            node.finishAdd(queue)
        }
    }

    impl public open class RemoveFirstDesc<T>(
            impl val queue: Node
    ) : AbstractAtomicDesc() {
        @Suppress("UNCHECKED_CAST")
        impl public val result: T
            get() = affectedNode!! as T

        impl final override fun takeAffectedNode(op: OpDescriptor): Node = queue.next as Node
        impl final override var affectedNode: Node? = null
        impl final override var originalNext: Node? = null

        // check node predicates here, must signal failure if affect is not of type T
        impl protected override fun failure(affected: Node, next: Any): Any? =
                if (affected === queue) LIST_EMPTY else null

        // validate the resulting node (return false if it should be deleted)
        impl protected open fun validatePrepared(node: T): Boolean = true // false means remove node & retry

        impl final override fun retry(affected: Node, next: Any): Boolean {
            if (next !is Removed) return false
            affected.helpDelete() // must help delete, or loose lock-freedom
            return true
        }

        @Suppress("UNCHECKED_CAST")
        impl final override fun onPrepare(affected: Node, next: Node): Any? {
            check(affected !is LockFreeLinkedListHead)
            if (!validatePrepared(affected as T)) return REMOVE_PREPARED
            affectedNode = affected
            originalNext = next
            return null // ok
        }

        impl final override fun updatedNext(affected: Node, next: Node): Any = next.removed()
        impl final override fun finishOnSuccess(affected: Node, next: Node) = affected.finishRemove(next)
    }

    impl public abstract class AbstractAtomicDesc : AtomicDesc() {
        impl protected abstract val affectedNode: Node?
        impl protected abstract val originalNext: Node?
        impl protected open fun takeAffectedNode(op: OpDescriptor): Node = affectedNode!!
        impl protected open fun failure(affected: Node, next: Any): Any? = null // next: Node | Removed
        impl protected open fun retry(affected: Node, next: Any): Boolean = false // next: Node | Removed
        impl protected abstract fun onPrepare(affected: Node, next: Node): Any? // non-null on failure
        impl protected abstract fun updatedNext(affected: Node, next: Node): Any
        impl protected abstract fun finishOnSuccess(affected: Node, next: Node)

        // This is Harris's RDCSS (Restricted Double-Compare Single Swap) operation
        // It inserts "op" descriptor of when "op" status is still undecided (rolls back otherwise)
        private class PrepareOp(
                val next: Node,
                val op: AtomicOp<Node>,
                val desc: AbstractAtomicDesc
        ) : OpDescriptor() {
            override fun perform(affected: Any?): Any? {
                affected as Node // type assertion
                val decision = desc.onPrepare(affected, next)
                if (decision != null) {
                    if (decision === REMOVE_PREPARED) {
                        // remove element on failure
                        val removed = next.removed()
                        if (affected._next == this) {
                            affected._next = removed
                            affected.helpDelete()
                        }
                    } else {
                        // some other failure -- mark as decided
                        op.tryDecide(decision)
                        // undo preparations
                        if (affected._next == this) affected._next = next
                    }
                    return decision
                }
                check(desc.affectedNode === affected)
                check(desc.originalNext === next)
                val update: Any = if (op.isDecided) next else op // restore if decision was already reached
                if (affected._next == this) affected._next = update
                return null // ok
            }
        }

        @Suppress("UNCHECKED_CAST")
        impl final override fun prepare(op: AtomicOp<*>): Any? {
            while (true) { // lock free loop on next
                val affected = takeAffectedNode(op)
                // read its original next pointer first
                val next = affected._next
                // then see if already reached consensus on overall operation
                if (op.isDecided) return null // already decided -- go to next desc
                if (next === op) return null // already in process of operation -- all is good
                if (next is OpDescriptor) {
                    // some other operation is in process -- help it
                    next.perform(affected)
                    continue // and retry
                }
                // next: Node | Removed
                val failure = failure(affected, next)
                if (failure != null) return failure // signal failure
                if (retry(affected, next)) continue // retry operation
                val prepareOp = PrepareOp(next as Node, op as AtomicOp<Node>, this)
                if (affected._next == next) {
                    affected._next = prepareOp
                    // prepared -- complete preparations
                    val prepFail = prepareOp.perform(affected)
                    if (prepFail === REMOVE_PREPARED) continue // retry
                    return prepFail
                }
            }
        }

        impl final override fun complete(op: AtomicOp<*>, failure: Any?) {
            val success = failure == null
            val affectedNode = affectedNode ?: run { check(!success); return }
            val originalNext = this.originalNext ?: run { check(!success); return }
            val update = if (success) updatedNext(affectedNode, originalNext) else originalNext
            if (affectedNode._next == op) {
                affectedNode._next = update
                if (success) finishOnSuccess(affectedNode, originalNext)
            }
        }
    }

    // ------ other helpers ------

    private fun finishAdd(next: Node) {
        while (true) {
            val nextPrev = next._prev
            if (nextPrev is Removed || this.next !== next) return // next was removed, remover fixes up links
            if (next._prev == nextPrev) {
                next._prev = this
                if (this.next is Removed) {
                    // already removed
                    next.correctPrev(nextPrev as Node, null)
                }
                return
            }
        }
    }

    private fun finishRemove(next: Node) {
        helpDelete()
        next.correctPrev(_prev.unwrap(), null)
    }

    private fun markPrev(): Node {
        while (true) { // lock-free loop on prev
            val prev = this._prev
            if (prev is Removed) return prev.ref
            val removed = (prev as Node).removed()
            if (_prev == prev) {
                _prev = removed
                return prev
            }
        }
    }

    // fixes next links to the left of this node
    @PublishedApi
    impl internal fun helpDelete() {
        var last: Node? = null // will set to the node left of prev when found
        var prev: Node = markPrev()
        var next: Node = (this._next as Removed).ref
        while (true) {
            // move to the right until first non-removed node
            val nextNext = next.next
            if (nextNext is Removed) {
                next.markPrev()
                next = nextNext.ref
                continue
            }
            // move the the left until first non-removed node
            val prevNext = prev.next
            if (prevNext is Removed) {
                if (last != null) {
                    prev.markPrev()
                    if (last._next == prev) last._next = prevNext.ref
                    prev = last
                    last = null
                } else {
                    prev = prev._prev.unwrap()
                }
                continue
            }
            if (prevNext !== this) {
                // skipped over some removed nodes to the left -- setup to fixup the next links
                last = prev
                prev = prevNext as Node
                if (prev === next) return // already done!!!
                continue
            }
            // Now prev & next are Ok
            if (prev._next == this) {
                prev._next = next
                return // success!
            }
        }
    }

    // fixes prev links from this node
    private fun correctPrev(_prev: Node, op: OpDescriptor?) {
        var prev: Node = _prev
        var last: Node? = null // will be set so that last.next === prev
        while (true) {
            // move the the left until first non-removed node
            val prevNext = prev._next
            if (prevNext === op) return // part of the same op -- don't recurse
            if (prevNext is OpDescriptor) { // help & retry
                prevNext.perform(prev)
                continue
            }
            if (prevNext is Removed) {
                if (last !== null) {
                    prev.markPrev()
                    if (last._next == prev) {
                        last._next = prevNext.ref
                    }
                    prev = last
                    last = null
                } else {
                    prev = prev._prev.unwrap()
                }
                continue
            }
            val oldPrev = this._prev
            if (oldPrev is Removed) return // this node was removed, too -- its remover will take care
            if (prevNext !== this) {
                // need to fixup next
                last = prev
                prev = prevNext as Node
                continue
            }
            if (oldPrev === prev) return // it is already linked as needed
            if (this._prev == oldPrev) {
                this._prev = prev
                if (prev._prev !is Removed) return // finish only if prev was not concurrently removed
            }
        }
    }

    impl internal fun validateNode(prev: Node, next: Node) {
        check(prev === this._prev)
        check(next === this._next)
    }

    // TODO
    // override fun toString(): String = "${this::class.java.simpleName}@${Integer.toHexString(System.identityHashCode(this))}"
}