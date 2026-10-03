/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AttachmentFileWriterTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `successful copy replaces the previous file and closes input`() {
        val target = directory.newFile("attachment")
        target.writeText("old")
        val bytes = ByteArray(20000) { (it % 127).toByte() }
        val input = TrackingInput(bytes)
        var copied = 0L

        AttachmentFileWriter.write(input, target, { false }) { copied = it }

        assertArrayEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong(), copied)
        assertTrue(input.closed)
        assertEquals(listOf("attachment"), directory.root.list()!!.toList())
    }

    @Test
    fun `read failure preserves previous attachment and removes partial file`() {
        val target = directory.newFile("attachment")
        target.writeText("old")
        val input = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("read failed")
            override fun close() {
                closed = true
            }
        }

        assertThrows(IOException::class.java) {
            AttachmentFileWriter.write(input, target, { false }) {}
        }

        assertEquals("old", target.readText())
        assertTrue(input.closed)
        assertEquals(listOf("attachment"), directory.root.list()!!.toList())
    }

    @Test
    fun `cancellation during copy leaves no published or partial attachment`() {
        val input = TrackingInput(ByteArray(20000))
        val target = File(directory.root, "attachment")
        var stopped = false

        assertThrows(InterruptedIOException::class.java) {
            AttachmentFileWriter.write(input, target, { stopped }) { stopped = true }
        }

        assertTrue(input.closed)
        assertFalse(target.exists())
        assertTrue(directory.root.list()!!.isEmpty())
    }

    @Test
    fun `failed publication removes temporary file and closes input`() {
        val target = directory.newFolder("attachment")
        File(target, "keep").writeText("existing")
        val input = TrackingInput("download".toByteArray())

        assertThrows(IOException::class.java) {
            AttachmentFileWriter.write(input, target, { false }) {}
        }

        assertTrue(input.closed)
        assertEquals("existing", File(target, "keep").readText())
        assertEquals(listOf("attachment"), directory.root.list()!!.toList())
    }

    @Test
    fun `overlapping downloads use independent temporary files`() {
        val target = File(directory.root, "attachment")
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit {
                AttachmentFileWriter.write(TrackingInput("first".toByteArray()), target, { false }) {
                    started.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            AttachmentFileWriter.write(TrackingInput("second".toByteArray()), target, { false }) {}
            assertEquals("second", target.readText())
            resume.countDown()
            first.get(2, TimeUnit.SECONDS)

            assertEquals("first", target.readText())
            assertEquals(listOf("attachment"), directory.root.list()!!.toList())
        } finally {
            resume.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
