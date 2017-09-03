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
impl public abstract class AtomicOp<in T> : OpDescriptor() {
    private var _consensus: Any? = UNDECIDED

    companion object {
        private val UNDECIDED: Any = Symbol("UNDECIDED")
    }

    impl val isDecided: Boolean get() = _consensus !== UNDECIDED

    impl fun tryDecide(decision: Any?): Boolean {
        check(decision !== UNDECIDED)
        return if (_consensus == UNDECIDED) {
            _consensus = decision
            true
        } else false
    }

    private fun decide(decision: Any?): Any? = if (tryDecide(decision)) decision else _consensus

    impl abstract fun prepare(affected: T): Any? // `null` if Ok, or failure reason

    impl abstract fun complete(affected: T, failure: Any?) // failure != null if failed to prepare op

    // returns `null` on success
    @Suppress("UNCHECKED_CAST")
    impl final override fun perform(affected: Any?): Any? {
        // make decision on status
        var decision = this._consensus
        if (decision === UNDECIDED)
            decision = decide(prepare(affected as T))
        complete(affected as T, decision)
        return decision
    }
}