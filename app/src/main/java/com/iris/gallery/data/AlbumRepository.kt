package com.iris.gallery.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.iris.gallery.ui.MediaAlbum
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

enum class AlbumAction {
    MOVE,
    COPY
}

data class AlbumOperationResult(
    val successCount: Int,
    val failedCount: Int,
    val targetAlbumName: String,
    val action: AlbumAction,
    val movedMedia: List<MediaImage> = emptyList(),
)

class AlbumRepository(private val context: Context) {

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    suspend fun moveMedia(
        mediaList: List<MediaImage>,
        targetDir: File,
        targetAlbumName: String
    ): AlbumOperationResult = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        var success = 0
        var failed = 0
        val moved = mutableListOf<MediaImage>()
        val hasManager = hasAllFilesAccess()

        for (item in mediaList) {
            val srcFile = if (item.path.isNotBlank()) File(item.path) else null
            val destFile = getUniqueDestinationFile(targetDir, item.name.ifBlank { "media_${System.currentTimeMillis()}" })

            var done = false
            if (srcFile != null && srcFile.exists() && srcFile.parentFile?.canonicalPath == targetDir.canonicalPath) {
                done = true
            }

            if (!done) {
                // 1. Try atomic rename first if source file exists
                if (srcFile != null && srcFile.exists()) {
                    done = runCatching {
                        srcFile.renameTo(destFile)
                    }.getOrDefault(false)
                    if (done) {
                        deleteSourceMediaStoreRow(item)
                    }
                }

                // 2. Fallback to copy and delete if rename was not possible
                if (!done) {
                    var copied = false
                    if (srcFile != null && srcFile.exists()) {
                        copied = runCatching {
                            srcFile.copyTo(destFile, overwrite = true).exists() && destFile.length() > 0
                        }.getOrDefault(false)
                    }
                    if (!copied) {
                        copied = runCatching {
                            context.contentResolver.openInputStream(item.uri)?.use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            destFile.exists() && destFile.length() > 0
                        }.getOrDefault(false)
                    }
                    val srcLen = if (srcFile != null && srcFile.exists()) srcFile.length() else item.sizeBytes
                    val copyVerified = copied && destFile.exists() && destFile.length() > 0 &&
                        (srcLen <= 0L || destFile.length() == srcLen)

                    if (copyVerified) {
                        val deleted = deleteSourceMedia(item)
                        if (deleted || (srcFile != null && !srcFile.exists())) {
                            done = true
                        } else {
                            // If source file still exists, try direct delete
                            done = if (srcFile != null) runCatching { srcFile.delete() }.getOrDefault(false) else true
                        }
                        if (!done) {
                            // Source deletion failed: clean up destFile to avoid orphan duplicate
                            runCatching { destFile.delete() }
                        }
                    } else if (copied) {
                        // Incomplete copy: clean up destFile
                        runCatching { destFile.delete() }
                    }
                }
            }

            if (done) {
                success++
                if (item.dateModified > 0) {
                    runCatching { destFile.setLastModified(item.dateModified) }
                } else if (item.dateTaken > 0) {
                    runCatching { destFile.setLastModified(item.dateTaken) }
                }
                val targetBucketId = targetDir.absolutePath.lowercase(java.util.Locale.ROOT).hashCode().toLong()
                val tempId = -abs(destFile.absolutePath.hashCode().toLong()).coerceAtLeast(1L)
                val newMedia = item.copy(
                    id = tempId,
                    path = destFile.absolutePath,
                    name = destFile.name,
                    uri = android.net.Uri.fromFile(destFile),
                    bucketId = targetBucketId,
                    bucketName = targetAlbumName
                )
                moved.add(newMedia)
                val pathsToScan = if (srcFile != null && srcFile.absolutePath != destFile.absolutePath) {
                    arrayOf(srcFile.absolutePath, destFile.absolutePath)
                } else {
                    arrayOf(destFile.absolutePath)
                }
                MediaScannerConnection.scanFile(context, pathsToScan, null, null)
            } else {
                failed++
            }
        }

        AlbumOperationResult(
            successCount = success,
            failedCount = failed,
            targetAlbumName = targetAlbumName,
            action = AlbumAction.MOVE,
            movedMedia = moved
        )
    }

    suspend fun copyMedia(
        mediaList: List<MediaImage>,
        targetDir: File,
        targetAlbumName: String
    ): AlbumOperationResult = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        var success = 0
        var failed = 0
        val copied = mutableListOf<MediaImage>()
        val hasManager = hasAllFilesAccess()

        for (item in mediaList) {
            val srcFile = if (item.path.isNotBlank()) File(item.path) else null
            val destFile = getUniqueDestinationFile(targetDir, item.name.ifBlank { "media_${System.currentTimeMillis()}" })

            var done = false
            if (hasManager && srcFile != null && srcFile.exists()) {
                done = runCatching {
                    srcFile.copyTo(destFile, overwrite = false).exists()
                }.getOrDefault(false)
            }

            if (!done) {
                val copyOk = runCatching {
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    destFile.exists() && destFile.length() > 0
                }.getOrDefault(false)
                if (copyOk) done = true
            }

            if (done) {
                success++
                copied.add(item)
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            } else {
                failed++
            }
        }

        AlbumOperationResult(
            successCount = success,
            failedCount = failed,
            targetAlbumName = targetAlbumName,
            action = AlbumAction.COPY,
            movedMedia = copied
        )
    }

    fun getAlbumDirectory(album: MediaAlbum): File {
        val samplePath = album.images.firstOrNull()?.path
        if (!samplePath.isNullOrBlank()) {
            val parent = File(samplePath).parentFile
            if (parent != null && parent.exists()) {
                return parent
            }
        }
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), album.name)
    }

    fun createNewAlbumDirectory(albumName: String): File {
        val cleanName = albumName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), cleanName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getUniqueDestinationFile(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        if (!file.exists()) return file

        val nameWithoutExtension = fileName.substringBeforeLast(".")
        val extension = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ""
        var index = 1
        while (file.exists()) {
            file = File(dir, "${nameWithoutExtension}_$index$extension")
            index++
        }
        return file
    }

    private fun deleteSourceMedia(item: MediaImage): Boolean {
        var fileDeleted = false
        if (item.path.isNotBlank()) {
            val file = File(item.path)
            if (file.exists()) {
                val fDel = runCatching { file.delete() }.getOrDefault(false)
                val cDel = if (!fDel && file.exists()) runCatching { file.canonicalFile.delete() }.getOrDefault(false) else false
                fileDeleted = fDel || cDel || !file.exists()
            } else {
                fileDeleted = true
            }
        }
        val mediaStoreUri = if (item.uri.authority == "media") {
            item.uri.buildUpon().clearQuery().build()
        } else if (item.id > 0) {
            if (item.isVideo) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
            else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)
        } else {
            item.uri
        }
        val rowDeleted = runCatching { context.contentResolver.delete(mediaStoreUri, null, null) > 0 }.getOrDefault(false)
        if (item.id > 0) {
            val table = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns._ID}=?", arrayOf(item.id.toString()))
            }
        }
        if (item.uri != mediaStoreUri) {
            runCatching { context.contentResolver.delete(item.uri, null, null) }
        }
        if (item.path.isNotBlank()) {
            val table = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path))
            }
            runCatching {
                context.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path))
            }
            MediaScannerConnection.scanFile(context, arrayOf(item.path), null, null)
        }
        return fileDeleted || rowDeleted
    }

    private fun deleteSourceMediaStoreRow(item: MediaImage) {
        val mediaStoreUri = if (item.uri.authority == "media") {
            item.uri.buildUpon().clearQuery().build()
        } else if (item.id > 0) {
            if (item.isVideo) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
            else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)
        } else {
            item.uri
        }
        runCatching { context.contentResolver.delete(mediaStoreUri, null, null) }
        if (item.id > 0) {
            val table = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns._ID}=?", arrayOf(item.id.toString()))
            }
        }
        if (item.uri != mediaStoreUri) {
            runCatching { context.contentResolver.delete(item.uri, null, null) }
        }
        if (item.path.isNotBlank()) {
            val table = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path))
            }
            runCatching {
                context.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path))
            }
            MediaScannerConnection.scanFile(context, arrayOf(item.path), null, null)
        }
    }
}
