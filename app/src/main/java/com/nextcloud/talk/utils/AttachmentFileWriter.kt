/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AttachmentFileWriter {
    /** Takes ownership of [input] and replaces [target] only after the complete copy succeeds. */
    fun write(input: InputStream, target: File, isStopped: () -> Boolean, onProgress: (Long) -> Unit) {
        var temporary: File? = null
        try {
            input.use { source ->
                val partial = File.createTempFile(".download-", ".part", target.parentFile)
                temporary = partial
                partial.outputStream().use { output ->
                    copy(source, output, isStopped, onProgress)
                }
            }
            checkActive(isStopped)
            Files.move(
                checkNotNull(temporary).toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temporary?.delete()
        }
    }

    private fun copy(input: InputStream, output: OutputStream, isStopped: () -> Boolean, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkActive(isStopped)
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
            total += count
            onProgress(total)
        }
    }

    private fun checkActive(isStopped: () -> Boolean) {
        if (isStopped() || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Attachment download stopped")
        }
    }
}
