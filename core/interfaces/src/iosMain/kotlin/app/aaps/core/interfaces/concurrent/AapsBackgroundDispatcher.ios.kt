package app.aaps.core.interfaces.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO

/**
 * Kotlin/Native gives no way to lower a coroutine thread's priority, so this is two threads of the IO
 * pool at normal priority. The work still never runs on `Dispatchers.Default`, where the loop runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
actual val aapsBackgroundDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
