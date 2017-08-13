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

import kotlin.jvm.JvmField

internal typealias Node = LockFreeLinkedListNode

@PublishedApi
internal const val UNDECIDED = 0

@PublishedApi
internal const val SUCCESS = 1

@PublishedApi
internal const val FAILURE = 2

@PublishedApi
internal val CONDITION_FALSE: Any = Symbol("CONDITION_FALSE")

@PublishedApi
internal val ALREADY_REMOVED: Any = Symbol("ALREADY_REMOVED")

@PublishedApi
internal val LIST_EMPTY: Any = Symbol("LIST_EMPTY")

internal val REMOVE_PREPARED: Any = Symbol("REMOVE_PREPARED")

/**
 * @suppress **This is unstable API and it is subject to change.**
 */
public typealias RemoveFirstDesc<T> = LockFreeLinkedListNode.RemoveFirstDesc<T>

/**
 * @suppress **This is unstable API and it is subject to change.**
 */
public typealias AddLastDesc<T> = LockFreeLinkedListNode.AddLastDesc<T>

internal class Removed(@JvmField val ref: Node) {
    override fun toString(): String = "Removed[$ref]"
}

@PublishedApi
internal fun Any.unwrap(): Node = if (this is Removed) ref else this as Node

/**
 * Head (sentinel) item of the linked list that is never removed.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
public open class LockFreeLinkedListHead : LockFreeLinkedListNode() {
    public val isEmpty: Boolean get() = next === this

    /**
     * Iterates over all elements in this list of a specified type.
     */
    public inline fun <reified T : Node> forEach(block: (T) -> Unit) {
        var cur: Node = next as Node
        while (cur != this) {
            if (cur is T) block(cur)
            cur = cur.next.unwrap()
        }
    }

    // just a defensive programming -- makes sure that list head sentinel is never removed
    public final override fun remove() = throw UnsupportedOperationException()
    public final override fun describeRemove(): AtomicDesc? = throw UnsupportedOperationException()

    internal fun validate() {
        var prev: Node = this
        var cur: Node = next as Node
        while (cur != this) {
            val next = cur.next.unwrap()
            cur.validateNode(prev, next)
            prev = cur
            cur = next
        }
        validateNode(prev, next as Node)
    }
}
