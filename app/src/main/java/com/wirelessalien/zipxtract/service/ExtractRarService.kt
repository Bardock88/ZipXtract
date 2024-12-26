/*
 *  Copyright (C) 2023  WirelessAlien <https://github.com/WirelessAlien>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wirelessalien.zipxtract.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wirelessalien.zipxtract.ArchiveOpenMultipartRarCallback
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRACTION_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class ExtractRarService : Service() {

    companion object {
        const val NOTIFICATION_ID = 22
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_USE_APP_NAME_DIR = "use_app_name_dir"

    }

    private var password: CharArray? = null
    private var extractionJob: Job? = null
    private var archiveFormat: ArchiveFormat? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH)
        val useAppNameDir = intent?.getBooleanExtra(EXTRA_USE_APP_NAME_DIR, false) ?: false
        val password = intent?.getStringExtra(EXTRA_PASSWORD)
        this.password = password?.toCharArray()

        if (filePath.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val modifiedFilePath = getModifiedFilePath(filePath)

        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            extractArchive(modifiedFilePath, useAppNameDir)
        }

        return START_NOT_STICKY
    }

    private fun getModifiedFilePath(filePath: String): String {
        val file = File(filePath)
        val fileName = file.name
        val modifiedFileName = when {
            fileName.matches(Regex(".*\\.part\\d+\\.rar")) -> fileName.replace(Regex("\\.part\\d+\\.rar"), ".part1.rar")
            fileName.matches(Regex(".*\\.r\\d{2}")) -> fileName.replace(Regex("\\.r\\d{2}"), ".r00")
            fileName.matches(Regex(".*\\.\\d{3}")) -> fileName.replace(Regex("\\.\\d{3}"), ".001")
            else -> fileName
        }
        return File(file.parent, modifiedFileName).path
    }

    override fun onDestroy() {
        super.onDestroy()
        extractionJob?.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EXTRACTION_NOTIFICATION_CHANNEL_ID,
                getString(R.string.extract_archive_notification_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_ongoing))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun extractArchive(filePath: String, useAppNameDir: Boolean) {

        if (filePath.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        val file = File(filePath)
        val parentDir: File
        if (useAppNameDir) {
            val rootDir = File(Environment.getExternalStorageDirectory().absolutePath)
            parentDir = File(rootDir, getString(R.string.app_name))
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
        } else {
            parentDir = file.parentFile ?: return
        }

        val baseFileName = file.nameWithoutExtension
        var newFileName = baseFileName
        var destinationDir = File(parentDir, newFileName)
        var counter = 1

        while (destinationDir.exists()) {
            newFileName = "$baseFileName ($counter)"
            destinationDir = File(parentDir, newFileName)
            counter++
        }

        try {
            val archiveOpenVolumeCallback = ArchiveOpenMultipartRarCallback(parentDir)
            val inStream: IInStream? = archiveOpenVolumeCallback.getStream(file.name)
            if (inStream != null) {
                val inArchive: IInArchive = SevenZip.openInArchive(archiveFormat, inStream, archiveOpenVolumeCallback)

                try {
                    val itemCount = inArchive.numberOfItems
                    for (i in 0 until itemCount) {
                        inArchive.getProperty(i, PropID.PATH) as String
                        destinationDir.mkdir()

                        try {
                            inArchive.extract(null, false, ExtractCallback(inArchive, destinationDir))
                        } catch (e: SevenZipException) {
                            e.printStackTrace()
                            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(
                                EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
                            return
                        }
                    }
                    showCompletionNotification()
                    sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.path))
                } catch (e: SevenZipException) {
                    e.printStackTrace()
                    showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                    sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(
                        EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
                } finally {
                    inArchive.close()
                    archiveOpenVolumeCallback.close()
                    if (useAppNameDir) {
                        filesDir.deleteRecursively()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(
                EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        }
    }

    private inner class ExtractCallback(
        private val inArchive: IInArchive,
        private val dstDir: File
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        private var uos: OutputStream? = null
        private var totalSize: Long = 0
        private var extractedSize: Long = 0

        init {
            totalSize = inArchive.numberOfItems.toLong()
        }

        override fun setOperationResult(p0: ExtractOperationResult?) {
            if (p0 == ExtractOperationResult.OK) {
                try {
                    uos?.close()
                    extractedSize++
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        override fun getStream(p0: Int, p1: ExtractAskMode?): ISequentialOutStream {

            val path: String = inArchive.getStringProperty(p0, PropID.PATH)
            val isDir: Boolean = inArchive.getProperty(p0, PropID.IS_FOLDER) as Boolean
            val unpackedFile = File(dstDir, path)

            if (isDir) {
                unpackedFile.mkdirs()
            } else {
                try {
                    val parentDir = unpackedFile.parentFile
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                    unpackedFile.createNewFile()
                    uos = FileOutputStream(unpackedFile)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            return ISequentialOutStream { data: ByteArray ->
                try {
                    if (!isDir) {
                        uos?.write(data)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                data.size
            }
        }

        override fun prepareOperation(p0: ExtractAskMode?) {}
        override fun setCompleted(complete: Long) {
            val progress = ((complete.toDouble() / totalSize) * 100).toInt()
            startForeground(NOTIFICATION_ID, createNotification(progress))
            updateProgress(progress)
        }

        override fun setTotal(p0: Long) {
            totalSize = p0
        }

        override fun cryptoGetTextPassword(): String {
            return String(password ?: CharArray(0))
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(ACTION_EXTRACTION_PROGRESS).putExtra(EXTRA_PROGRESS, progress))
    }

    private fun showCompletionNotification() {
        stopForegroundService()

        val notification = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_success))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(error: String) {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_failed))
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }
}