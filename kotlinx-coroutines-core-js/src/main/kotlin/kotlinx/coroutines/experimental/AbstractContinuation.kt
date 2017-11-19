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

package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.Continuation

/**
 * @suppress **This is unstable API and it is subject to change.**
 */
internal actual abstract class AbstractContinuation<in T> actual constructor(
        active: Boolean,
        protected actual val resumeMode: Int
) : JobSupport(active), Continuation<T> {
    private var decision = UNDECIDED

    /* decision state machine

        +-----------+   trySuspend   +-----------+
        | UNDECIDED | -------------> | SUSPENDED |
        +-----------+                +-----------+
              |
              | tryResume
              V
        +-----------+
        |  RESUMED  |
        +-----------+

        Note: both tryResume and trySuspend can be invoked at most once, first invocation wins
     */

    protected actual companion object {
        actual val UNDECIDED = 0
        actual val SUSPENDED = 1
        actual val RESUMED = 2
    }

    protected actual fun trySuspend(): Boolean {
        while (true) { // lock-free loop on decision
            val decision = this.decision // volatile read
            when (decision) {
                UNDECIDED -> if (this.decision == UNDECIDED) {
                    this.decision = SUSPENDED
                    return true
                }
                RESUMED -> return false
                else -> error("Already suspended")
            }
        }
    }

    protected actual fun tryResume(): Boolean {
        while (true) { // lock-free loop on decision
            val decision = this.decision // volatile read
            when (decision) {
                UNDECIDED -> if (this.decision == UNDECIDED) {
                    this.decision = RESUMED
                    return true
                }
                SUSPENDED -> return false
                else -> error("Already resumed")
            }
        }
    }

    actual override fun resume(value: T) = resumeImpl(value, resumeMode)

    protected actual fun resumeImpl(value: T, resumeMode: Int) {
        lockFreeLoopOnState { state ->
            when (state) {
                is Incomplete -> if (updateState(state, value, resumeMode)) return
                is Cancelled -> return // ignore resumes on cancelled continuation
                else -> error("Already resumed, but got value $value")
            }
        }
    }

    actual override fun resumeWithException(exception: Throwable) = resumeWithExceptionImpl(exception, resumeMode)

    protected actual fun resumeWithExceptionImpl(exception: Throwable, resumeMode: Int) {
        lockFreeLoopOnState { state ->
            when (state) {
                is Incomplete -> {
                    if (updateState(state, CompletedExceptionally(exception), resumeMode)) return
                }
                is Cancelled -> {
                    // ignore resumes on cancelled continuation, but handle exception if a different one is here
                    if (exception != state.exception) handleCoroutineException(context, exception)
                    return
                }
                else -> throw IllegalStateException("Already resumed, but got exception $exception")
            }
        }
    }

    actual override fun handleException(exception: Throwable) {
        handleCoroutineException(context, exception)
    }
}
