/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.api

import com.nextcloud.talk.logger.Level
import com.nextcloud.talk.logger.Logger
import com.nextcloud.talk.logger.LogsRepository
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class LoggingHttpInterceptor @Inject constructor(
    private val logger: Logger,
    private val logsRepository: LogsRepository
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (logsRepository.minimumLevel > Level.DEBUG) {
            return chain.proceed(chain.request())
        }
        // URLs, headers, bodies and exception messages may contain credentials or private conversation data.
        val started = System.nanoTime()
        return try {
            chain.proceed(chain.request()).also { response ->
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                logger.d("HTTP", "Response ${response.code} received in ${elapsedMs}ms")
            }
        } catch (exception: IOException) {
            logger.d("HTTP", "Request failed (${exception.javaClass.simpleName})")
            throw exception
        }
    }
}
