/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class MediaStoreFileWriterTest {
    @get:Rule
    val directory = TemporaryFolder()
    private val resolver = mock<ContentResolver>()
    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val destination = Uri.parse("content://media/external/images/media/42")
    private lateinit var source: File
    private val output = object : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Before
    fun setUp() {
        source = directory.newFile("photo.jpg").apply { writeText("image-content") }
        whenever(resolver.insert(eq(collection), any())).thenReturn(destination)
        whenever(resolver.openOutputStream(destination)).thenReturn(output)
        whenever(resolver.update(eq(destination), any(), isNull(), isNull())).thenReturn(1)
    }

    @Test
    fun `publishes complete output and closes the stream`() {
        assertEquals(destination, MediaStoreFileWriter.write(resolver, collection, ContentValues(), source))

        assertEquals("image-content", output.toString())
        assertTrue(output.closed)
        val values = argumentCaptor<ContentValues>()
        verify(resolver).insert(eq(collection), values.capture())
        assertEquals(1, values.firstValue.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        verify(resolver).update(eq(destination), values.capture(), isNull(), isNull())
        assertEquals(0, values.lastValue.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        verify(resolver, never()).delete(any(), isNull(), isNull())
    }

    @Test
    fun `null insertion is an error instead of a successful save`() {
        whenever(resolver.insert(eq(collection), any())).thenReturn(null)

        assertThrows(IOException::class.java) {
            MediaStoreFileWriter.write(resolver, collection, ContentValues(), source)
        }

        verify(resolver, never()).openOutputStream(any())
    }

    @Test
    fun `null output stream removes the inserted entry`() {
        whenever(resolver.openOutputStream(destination)).thenReturn(null)

        assertThrows(IOException::class.java) {
            MediaStoreFileWriter.write(resolver, collection, ContentValues(), source)
        }

        verify(resolver).delete(destination, null, null)
    }

    @Test
    fun `unreadable source closes output and removes the inserted entry`() {
        source.delete()

        assertThrows(IOException::class.java) {
            MediaStoreFileWriter.write(resolver, collection, ContentValues(), source)
        }

        assertTrue(output.closed)
        verify(resolver).delete(destination, null, null)
        verify(resolver, never()).update(any(), any(), isNull(), isNull())
    }

    @Test
    fun `failed publication removes the incomplete entry`() {
        whenever(resolver.update(eq(destination), any(), isNull(), isNull())).thenReturn(0)

        assertThrows(IOException::class.java) {
            MediaStoreFileWriter.write(resolver, collection, ContentValues(), source)
        }

        verify(resolver).delete(destination, null, null)
    }

    @Test
    fun `cleanup failure retains the original exception`() {
        val failure = SecurityException("permission revoked")
        whenever(resolver.openOutputStream(destination)).thenThrow(failure)
        whenever(resolver.delete(destination, null, null)).thenThrow(SecurityException("cleanup denied"))

        val actual = assertThrows(SecurityException::class.java) {
            MediaStoreFileWriter.write(resolver, collection, ContentValues(), source)
        }

        assertSame(failure, actual)
        assertEquals(1, actual.suppressed.size)
    }

    @Test
    @Config(sdk = [28])
    fun `legacy media insertion does not use pending columns`() {
        MediaStoreFileWriter.write(resolver, collection, ContentValues(), source)

        val values = argumentCaptor<ContentValues>()
        verify(resolver).insert(eq(collection), values.capture())
        assertTrue(!values.firstValue.containsKey(MediaStore.MediaColumns.IS_PENDING))
        verify(resolver, never()).update(any(), any(), isNull(), isNull())
    }
}
