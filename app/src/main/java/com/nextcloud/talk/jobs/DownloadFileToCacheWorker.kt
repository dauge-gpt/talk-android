/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2021 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.AttachmentFileWriter
import com.nextcloud.talk.utils.FileUtils
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.preferences.AppPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import java.io.IOException
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class DownloadFileToCacheWorker(val context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    private var totalFileSize: Long = -1

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun doWork(): Result {
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)

        return try {
            val currentUser = runBlocking { userManager.getUserWithId(inputData.getLong(KEY_INTERNAL_USER_ID, 0L)) }
            val attachmentFolder = inputData.getString(KEY_ATTACHMENT_FOLDER)
            val fileId = inputData.getString(KEY_FILE_ID)
            val fileName = inputData.getString(KEY_FILE_NAME)
            val remotePath = inputData.getString(KEY_FILE_PATH)
            totalFileSize = (inputData.getLong(KEY_FILE_SIZE, -1))
            if (totalFileSize > 0) {
                setProgressAsync(Data.Builder().putInt(PROGRESS, 0).build())
            }

            checkNotNull(currentUser)
            val baseUrl = checkNotNull(currentUser.baseUrl)
            val userId = checkNotNull(currentUser.userId)
            checkNotNull(attachmentFolder)
            checkNotNull(fileName)
            checkNotNull(remotePath)

            val url = ApiUtils.getUrlForFileDownload(baseUrl, userId, remotePath)

            downloadFile(currentUser, url, fileId, fileName)
        } catch (e: IllegalStateException) {
            Log.e(javaClass.simpleName, "Something went wrong when trying to download file", e)
            Result.failure()
        } catch (e: IOException) {
            Log.e(TAG, "Attachment download failed", e)
            Result.failure()
        }
    }

    private fun downloadFile(currentUser: User, url: String, fileId: String?, fileName: String): Result {
        val downloadCall = ncApi.downloadFile(
            ApiUtils.getCredentials(currentUser.username, currentUser.token),
            url
        )

        val response = downloadCall.execute()
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return Result.failure()
        }
        return executeDownload(response.body(), fileId, fileName)
    }

    private fun executeDownload(body: ResponseBody?, fileId: String?, fileName: String): Result =
        body?.use {
            val targetFile = if (fileId.isNullOrEmpty()) {
                FileUtils.resolveSharedAttachmentFile(context.cacheDir, fileName)
            } else {
                FileUtils.resolveSharedAttachmentFile(context.cacheDir, fileId, fileName)
            }
            if (targetFile == null) {
                Log.e(TAG, "Could not resolve attachment cache destination")
                return@use Result.failure()
            }

            var lastProgressTime = SystemClock.elapsedRealtime()
            AttachmentFileWriter.write(body.byteStream(), targetFile, { isStopped }) { total ->
                if (totalFileSize > 0) {
                    val currentTime = SystemClock.elapsedRealtime()
                    if (currentTime - lastProgressTime >= PROGRESS_THRESHOLD) {
                        val progress = (total.toDouble() / totalFileSize * COMPLETE_PERCENTAGE)
                            .toInt().coerceIn(0, COMPLETE_PERCENTAGE)
                        setProgressAsync(Data.Builder().putInt(PROGRESS, progress).build())
                        lastProgressTime = currentTime
                    }
                }
            }
            setProgressAsync(Data.Builder().putBoolean(SUCCESS, true).build())
            Result.success()
        } ?: Result.failure()

    companion object {
        const val TAG = "DownloadFileToCache"
        const val KEY_ATTACHMENT_FOLDER = "KEY_ATTACHMENT_FOLDER"
        const val KEY_FILE_ID = "KEY_FILE_ID"
        const val KEY_FILE_NAME = "KEY_FILE_NAME"
        const val KEY_FILE_PATH = "KEY_FILE_PATH"
        const val KEY_FILE_SIZE = "KEY_FILE_SIZE"
        const val PROGRESS = "PROGRESS"
        const val SUCCESS = "SUCCESS"
        const val COMPLETE_PERCENTAGE = 100
        const val PROGRESS_THRESHOLD = 50
    }
}
