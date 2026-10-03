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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.IOException

class LoggingHttpInterceptorTest {
    private val logger = mock<Logger>()
    private val logsRepository = mock<LogsRepository>()
    private val chain = mock<Interceptor.Chain>()
    private val interceptor = LoggingHttpInterceptor(logger, logsRepository)

    @Test
    fun `debug logging leaves streaming bodies untouched and excludes private data`() {
        var requestBodyWritten = false
        val requestBody = object : RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: BufferedSink) {
                requestBodyWritten = true
                sink.writeUtf8("private-request-body")
            }
        }
        val request = Request.Builder()
            .url("https://private-host.invalid/private-path?token=private-query")
            .header("Authorization", "private-authorization")
            .header("Cookie", "private-cookie")
            .post(requestBody)
            .build()
        val source = object : ForwardingSource(Buffer().writeUtf8("private-response-body")) {}.buffer()
        val body = ResponseBody.create(null, -1, source)
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("private-status-message")
            .header("Set-Cookie", "private-session")
            .body(body)
            .build()
        whenever(logsRepository.minimumLevel).thenReturn(Level.DEBUG)
        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(request)).thenReturn(response)

        interceptor.intercept(chain).use { actual ->
            assertSame(response, actual)
            assertEquals(0L, body.source().buffer.size)
            assertEquals("private-response-body", actual.body!!.string())
        }

        assertFalse(requestBodyWritten)
        val log = argumentCaptor<String>()
        verify(logger).d(eq("HTTP"), log.capture())
        assertTrue(log.firstValue.startsWith("Response 200 received in "))
        assertFalse(log.firstValue.contains("private-"))
        verify(chain).proceed(request)
    }

    @Test
    fun `failed request preserves exception without logging its message`() {
        val request = Request.Builder().url("https://example.invalid").build()
        val failure = IOException("private-token-in-url")
        whenever(logsRepository.minimumLevel).thenReturn(Level.DEBUG)
        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(request)).thenThrow(failure)

        assertSame(failure, assertThrows(IOException::class.java) { interceptor.intercept(chain) })

        verify(logger).d("HTTP", "Request failed (IOException)")
    }

    @Test
    fun `disabled debug logging passes response through without logging`() {
        val request = Request.Builder().url("https://example.invalid").build()
        val response = mock<Response>()
        whenever(logsRepository.minimumLevel).thenReturn(Level.WARNING)
        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(any())).thenReturn(response)

        assertSame(response, interceptor.intercept(chain))

        verifyNoInteractions(logger)
    }
}
