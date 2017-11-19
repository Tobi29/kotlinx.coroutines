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

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal actual object DefaultExecutor : EventLoopBase(), Runnable {
    actual override val canComplete: Boolean get() = false
    actual override val isCompleted: Boolean get() = false

    private var timeoutHandle: Int? = null

    actual override fun run() {
        timeoutHandle = null
        val next = TimeUnit.NANOSECONDS.toMillis(processNextEvent())
        if (next <= Int.MAX_VALUE) {
            timeoutHandle = setTimeout({ run() }, next.toInt())
        }
    }

    actual override fun unpark() {
        timeoutHandle?.let { clearTimeout(it) }
        timeoutHandle = setTimeout({ run() }, 0)
    }

    actual override fun isCorrectThread(): Boolean = true

    // used for tests
    internal actual fun ensureStarted() {
    }

    // used for tests
    internal actual fun shutdown(timeout: Long) {
        timeoutHandle?.let {
            clearTimeout(it)
            timeoutHandle = null
        }
    }
}

private external fun setTimeout(handler: dynamic,
                                timeout: Int = definedExternally,
                                vararg arguments: Any?): Int

private external fun clearTimeout(handle: Int = definedExternally)
