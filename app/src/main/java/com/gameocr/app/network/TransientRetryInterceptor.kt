package com.gameocr.app.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * Repete automaticamente requisições que falham por erro temporário do servidor
 * (ex.: Gemini 503 "model overloaded", 504, 429) com espera crescente: 2s, 4s, 8s.
 * Só repete antes de qualquer corpo ser lido, então não interfere em respostas em streaming.
 */
class TransientRetryInterceptor(
    private val maxAttempts: Int = 4,
    private val baseDelayMs: Long = 2_000L,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 1
        while (true) {
            val outcome = try {
                chain.proceed(chain.request())
            } catch (error: IOException) {
                if (chain.call().isCanceled() || attempt >= maxAttempts || error is java.io.InterruptedIOException) throw error
                null
            }
            if (outcome != null) {
                if (outcome.code !in RETRYABLE || attempt >= maxAttempts) return outcome
                val retryAfterMs = outcome.header("Retry-After")?.toLongOrNull()?.times(1000)
                outcome.close()
                sleep(chain, retryAfterMs ?: backoff(attempt))
            } else {
                sleep(chain, backoff(attempt))
            }
            Timber.w("Retry %d/%d for %s", attempt + 1, maxAttempts, chain.request().url.host)
            attempt++
        }
    }

    private fun backoff(attempt: Int) = baseDelayMs shl (attempt - 1)

    private fun sleep(chain: Interceptor.Chain, ms: Long) {
        try {
            Thread.sleep(ms.coerceIn(500L, 20_000L))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to retry", e)
        }
        if (chain.call().isCanceled()) throw IOException("Canceled")
    }

    private companion object {
        val RETRYABLE = setOf(429, 500, 502, 503, 504)
    }
}
