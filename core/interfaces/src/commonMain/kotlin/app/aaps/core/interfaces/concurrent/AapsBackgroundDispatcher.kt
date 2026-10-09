package app.aaps.core.interfaces.concurrent

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for work that nothing safety related waits for: display-only calculations (the
 * graph's insulin tail) and the AI assistant.
 *
 * Its threads run at the lowest priority where the platform allows it, so the system gives the CPU
 * to the calculation and the loop first. It is not `Dispatchers.Default`, where those run, so work
 * here can never take one of their threads either.
 *
 * Never use it for anything the loop, a pump command or an alarm waits for.
 */
expect val aapsBackgroundDispatcher: CoroutineDispatcher
