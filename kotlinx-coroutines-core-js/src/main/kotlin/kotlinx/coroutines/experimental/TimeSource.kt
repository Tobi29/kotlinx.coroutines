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

impl internal interface TimeSource {
    impl fun nanoTime(): Long
    impl fun trackTask(block: Runnable): Runnable
    impl fun unTrackTask()
    impl fun registerTimeLoopThread()
    impl fun unregisterTimeLoopThread()
}

internal object DefaultTimeSource : TimeSource {
    override fun nanoTime(): Long = (performance.now() * 1000000.0).toLong()
    override fun trackTask(block: Runnable): Runnable = block
    override fun unTrackTask() {}
    override fun registerTimeLoopThread() {}
    override fun unregisterTimeLoopThread() {}
}

impl internal var timeSource: TimeSource = DefaultTimeSource

private external val performance: Performance

private external abstract class Performance {
    fun now(): Double
}

