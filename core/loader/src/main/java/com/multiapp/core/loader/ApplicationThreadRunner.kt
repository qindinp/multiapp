package com.multiapp.core.loader

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

interface ApplicationThreadRunner {
    fun <T> run(block: () -> T): T
}

object DirectApplicationThreadRunner : ApplicationThreadRunner {
    override fun <T> run(block: () -> T): T = block()
}

class MainLooperApplicationThreadRunner(
    private val mainLooperProvider: () -> Looper = { Looper.getMainLooper() },
    private val currentLooperProvider: () -> Looper? = { Looper.myLooper() },
    private val handlerFactory: (Looper) -> Handler = ::Handler
) : ApplicationThreadRunner {
    override fun <T> run(block: () -> T): T {
        val mainLooper = mainLooperProvider()
        if (currentLooperProvider() === mainLooper) return block()

        val task = FutureTask<T> { block() }
        check(handlerFactory(mainLooper).post(task)) {
            "Unable to dispatch guest Application binding to the process main looper"
        }
        return try {
            task.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
