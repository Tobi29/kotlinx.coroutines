package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.Continuation

// **KLUDGE**: there is no reason to include continuation into debug string until the following ticket is resolved:
// KT-18986 Debug-friendly toString implementation for CoroutineImpl
// (the current string representation of continuation is useless and uses buggy reflection internals)
// So, this function is a replacement that extract a usable information from continuation -> its class name, at least
internal expect fun Continuation<*>.toDebugString(): String
