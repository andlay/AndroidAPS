package app.aaps.core.interfaces.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val backgroundThreadCount = AtomicInteger()

/**
 * Two daemon threads at the lowest priority (on Android the lowest nice level). They stop after a
 * minute without work, so there is no thread at all while nothing uses this.
 */
actual val aapsBackgroundDispatcher: CoroutineDispatcher =
    ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, "AapsBackground-${backgroundThreadCount.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.apply { allowCoreThreadTimeOut(true) }.asCoroutineDispatcher()
