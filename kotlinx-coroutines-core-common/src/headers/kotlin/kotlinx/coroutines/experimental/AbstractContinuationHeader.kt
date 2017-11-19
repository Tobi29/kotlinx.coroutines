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
header internal abstract class AbstractContinuation<in T>(
        active: Boolean,
        resumeMode: Int
) : JobSupport, Continuation<T> {
    protected val resumeMode: Int

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

    protected companion object {
        val UNDECIDED: Int
        val SUSPENDED: Int
        val RESUMED: Int
    }

    protected fun trySuspend(): Boolean

    protected fun tryResume(): Boolean

    override fun resume(value: T)

    protected fun resumeImpl(value: T, resumeMode: Int)

    override fun resumeWithException(exception: Throwable)

    protected fun resumeWithExceptionImpl(exception: Throwable, resumeMode: Int)

    override fun handleException(exception: Throwable)
}