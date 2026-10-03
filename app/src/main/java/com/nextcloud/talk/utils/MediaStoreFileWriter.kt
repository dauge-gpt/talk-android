/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException

internal object MediaStoreFileWriter {
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun write(resolver: ContentResolver, collection: Uri, values: ContentValues, source: File): Uri {
        val pendingValues = ContentValues(values)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pendingValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(collection, pendingValues)
            ?: throw IOException("Could not create MediaStore entry")
        try {
            val output = resolver.openOutputStream(destination)
                ?: throw IOException("Could not open MediaStore output")
            output.use { sink -> source.inputStream().use { it.copyTo(sink) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishedValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(destination, publishedValues, null, null) != 1) {
                    throw IOException("Could not publish MediaStore entry")
                }
            }
            return destination
        } catch (exception: Exception) {
            try {
                resolver.delete(destination, null, null)
            } catch (cleanupException: Exception) {
                exception.addSuppressed(cleanupException)
            }
            throw exception
        }
    }
}
