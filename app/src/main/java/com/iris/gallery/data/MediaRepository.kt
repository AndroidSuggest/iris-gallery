package com.iris.gallery.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.os.Bundle
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class MediaRepository(private val context: Context) {
    private val snapshot = AtomicFile(File(context.filesDir, "media_snapshot.bin"))
    private val libraryPreferences = LibraryPreferences(context)

    companion object {
        private const val VERIFIED_PATH_TTL_MS = 10 * 60 * 1_000L // 10 minutes
    }

    private val inMemoryCache = java.util.concurrent.ConcurrentHashMap<Long, MediaImage>()
    private val recentMovedOrDeletedIds = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val recentMovedOrDeletedPaths = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val verifiedPathsCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun loadSnapshot(): List<MediaImage> = runCatching {
        DataInputStream(snapshot.openRead().buffered()).use { input ->
            if (input.readInt() != 2) return@use emptyList()
            val list = List(input.readInt().coerceIn(0, 100_000)) {
                val id = input.readLong(); val isVideo = input.readBoolean()
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val name = input.readUTF(); val dateTaken = input.readLong()
                val width = input.readInt(); val height = input.readInt()
                val path = input.readUTF(); val bucketId = input.readLong(); val bucketName = input.readUTF()
                val durationMs = input.readLong(); val mimeType = input.readUTF(); val sizeBytes = input.readLong()
                val orientation = input.readInt(); val savedTitle = input.readUTF()
                val title = libraryPreferences.getCustomTitle(id) ?: savedTitle
                MediaImage(id, ContentUris.withAppendedId(collection, id), name, dateTaken,
                    width, height, path, bucketId, bucketName,
                    isVideo, durationMs, mimeType, sizeBytes, orientation, title)
            }
            val now = System.currentTimeMillis()
            list.forEach { item ->
                inMemoryCache[item.id] = item
                if (item.path.isNotBlank()) {
                    verifiedPathsCache[item.path] = now
                }
            }
            list
        }
    }.getOrDefault(emptyList())

    fun markMovedOrDeleted(ids: Set<Long>, paths: Set<String>) {
        val now = System.currentTimeMillis()
        ids.forEach {
            if (it > 0) {
                recentMovedOrDeletedIds[it] = now
                inMemoryCache.remove(it)
            }
        }
        paths.forEach {
            if (it.isNotBlank()) {
                recentMovedOrDeletedPaths[it] = now
                verifiedPathsCache.remove(it)
            }
        }
    }

    fun clearRecentMovedOrDeleted(ids: Set<Long>, paths: Set<String> = emptySet()) {
        ids.forEach { recentMovedOrDeletedIds.remove(it) }
        paths.forEach { recentMovedOrDeletedPaths.remove(it) }
    }

    fun clearVerifiedPathsCache() {
        verifiedPathsCache.clear()
        inMemoryCache.clear()
    }

    suspend fun rescanStorage(): Int = withContext(Dispatchers.IO) {
        verifiedPathsCache.clear()
        val storageRoots = mutableListOf<File>()
        runCatching { Environment.getExternalStorageDirectory()?.takeIf { it.exists() }?.let { storageRoots.add(it) } }
        runCatching {
            androidx.core.content.ContextCompat.getExternalFilesDirs(context, null).forEach { dir ->
                if (dir != null) {
                    val path = dir.absolutePath
                    val rootPath = path.substringBefore("/Android/data")
                    if (rootPath.isNotBlank() && rootPath != path) {
                        val rootFile = File(rootPath)
                        if (rootFile.exists() && rootFile !in storageRoots) {
                            storageRoots.add(rootFile)
                        }
                    }
                }
            }
        }

        val pathsToScan = mutableListOf<String>()
        val mediaFolders = listOf("DCIM", "Pictures", "Movies", "Download", "Documents")
        for (root in storageRoots) {
            pathsToScan.add(root.absolutePath)
            for (folder in mediaFolders) {
                val f = File(root, folder)
                if (f.exists()) {
                    pathsToScan.add(f.absolutePath)
                    f.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                        pathsToScan.add(sub.absolutePath)
                    }
                }
            }
        }

        if (pathsToScan.isNotEmpty()) {
            val countLatch = kotlinx.coroutines.CompletableDeferred<Int>()
            var scanned = 0
            android.media.MediaScannerConnection.scanFile(
                context,
                pathsToScan.toTypedArray(),
                null
            ) { _, _ ->
                scanned++
                if (scanned >= pathsToScan.size) {
                    countLatch.complete(scanned)
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(8_000) { countLatch.await() } ?: scanned
        } else {
            0
        }
    }

    suspend fun loadImages(trashed: Boolean = false): List<MediaImage> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        recentMovedOrDeletedIds.entries.removeIf { now - it.value > 15_000 }
        recentMovedOrDeletedPaths.entries.removeIf { now - it.value > 15_000 }

        val collectionsToQuery = if (android.os.Build.VERSION.SDK_INT >= 29) {
            val names = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrNull()?.filter { it.isNotBlank() }
            if (!names.isNullOrEmpty()) {
                names.map { MediaStore.Files.getContentUri(it) }
            } else {
                listOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
            }
        } else {
            listOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DATA,
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                MediaStore.Images.Media.RELATIVE_PATH
            } else {
                MediaStore.Images.Media.DATA
            },
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                MediaStore.MediaColumns.VOLUME_NAME
            } else {
                MediaStore.Images.Media.DATA
            },
        )

        val result = buildList {
            val mediaSelection = buildString {
                append("(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)")
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    if (!trashed) {
                        append(" AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
                    }
                } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                    append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
                }
            }
            val selectionArgs = buildList {
                add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
                add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            }.toTypedArray()
            val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

            for (collection in collectionsToQuery) {
                val cursorResult = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    context.contentResolver.query(collection, projection, Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, mediaSelection)
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,
                            if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                    }, null)
                } else {
                    context.contentResolver.query(collection, projection, mediaSelection, selectionArgs, order)
                }
                cursorResult?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val taken = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val added = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val width = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val height = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    val bucketId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val bucketName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                    val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val orientation = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
                    val title = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val relPathCol = if (android.os.Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
                    val volCol = if (android.os.Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) else -1

                    while (cursor.moveToNext()) {
                        val mediaId = cursor.getLong(id)
                        if (!trashed && recentMovedOrDeletedIds.containsKey(mediaId)) {
                            continue
                        }
                        val isVid = cursor.getInt(mediaType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val displayName = cursor.getString(name).orEmpty()
                        val rawData = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                        val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty() else ""
                        val volName = if (volCol >= 0) cursor.getString(volCol).orEmpty() else ""

                        val filePath = when {
                            rawData.isNotBlank() -> rawData
                            relPath.isNotBlank() && volName.isNotBlank() && volName != "external_primary" -> "/storage/$volName/$relPath$displayName"
                            relPath.isNotBlank() -> "/storage/emulated/0/$relPath$displayName"
                            else -> ""
                        }

                        val volumeForUri = if (volName.isNotBlank() && volName != "external_primary") volName else "external"
                        val baseMediaUri = if (isVid) {
                            ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volumeForUri), mediaId)
                        } else {
                            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volumeForUri), mediaId)
                        }
                        val mediaUri = if (trashed && android.os.Build.VERSION.SDK_INT >= 30) {
                            baseMediaUri.buildUpon().appendQueryParameter("include_trashed", "1").build()
                        } else {
                            baseMediaUri
                        }

                        if (!trashed && filePath.isNotBlank()) {
                            if (recentMovedOrDeletedPaths.containsKey(filePath)) {
                                continue
                            }
                            val isCachedValid = verifiedPathsCache[filePath]?.let { now - it < VERIFIED_PATH_TTL_MS } == true
                            var exists = isCachedValid
                            if (!exists) {
                                val file = File(filePath)
                                exists = file.exists()
                                if (!exists && volName.isNotBlank() && volName != "external_primary") {
                                    exists = runCatching {
                                        context.contentResolver.openAssetFileDescriptor(mediaUri, "r")?.use { true } ?: false
                                    }.getOrDefault(false)
                                }
                                if (exists) {
                                    verifiedPathsCache[filePath] = now
                                }
                            }
                            if (!exists) {
                                verifiedPathsCache.remove(filePath)
                                inMemoryCache.remove(mediaId)
                                android.media.MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                                if (android.os.Build.VERSION.SDK_INT <= 28) {
                                    runCatching { context.contentResolver.delete(mediaUri, null, null) }
                                }
                                continue
                            }
                        }

                        val existing = inMemoryCache[mediaId]
                        val takenTime = cursor.getLong(taken).takeIf { it > 0 }
                            ?: (cursor.getLong(added) * 1_000)
                        val itemWidth = cursor.getInt(width)
                        val itemHeight = cursor.getInt(height)
                        val itemDuration = cursor.getLong(duration)
                        val itemSize = cursor.getLong(size)
                        val itemOrientation = cursor.getInt(orientation)
                        val itemTitle = libraryPreferences.getCustomTitle(mediaId) ?: cursor.getString(title).orEmpty()

                        if (!trashed && existing != null &&
                            existing.name == displayName &&
                            existing.path == filePath &&
                            existing.dateTaken == takenTime &&
                            existing.sizeBytes == itemSize &&
                            existing.orientation == itemOrientation &&
                            existing.width == itemWidth &&
                            existing.height == itemHeight &&
                            existing.title == itemTitle &&
                            existing.isVideo == isVid
                        ) {
                            add(existing)
                            continue
                        }

                        val itemBucketId = if (trashed) -2L else cursor.getLong(bucketId)
                        val itemBucketName = if (trashed) "Trash" else cursor.getString(bucketName).orEmpty().ifBlank { "Other" }

                        val newImage = MediaImage(
                            id = mediaId,
                            uri = mediaUri,
                            name = displayName,
                            dateTaken = takenTime,
                            width = itemWidth,
                            height = itemHeight,
                            path = filePath,
                            bucketId = itemBucketId,
                            bucketName = itemBucketName,
                            isVideo = isVid,
                            durationMs = itemDuration,
                            mimeType = cursor.getString(mimeType).orEmpty(),
                            sizeBytes = itemSize,
                            orientation = itemOrientation,
                            title = itemTitle,
                        )
                        if (!trashed) {
                            inMemoryCache[mediaId] = newImage
                        }
                        add(newImage)
                    }
                }
            }
        }
        val sorted = result.distinctBy { it.id }.sortedWith(
            compareByDescending<MediaImage> { it.dateTaken }
                .thenByDescending { it.id }
        )
        if (!trashed) {
            val validIds = sorted.mapTo(HashSet(sorted.size)) { it.id }
            inMemoryCache.keys.retainAll(validIds)
            saveSnapshot(sorted)
        }
        sorted
    }

    suspend fun renameMedia(item: MediaImage, newName: String): MediaImage? = withContext(Dispatchers.IO) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) return@withContext null

        val currentExt = item.name.substringAfterLast('.', "")
        val finalName = if (trimmedName.contains('.') || currentExt.isEmpty()) trimmedName else "$trimmedName.$currentExt"
        if (finalName == item.name) return@withContext item

        val srcFile = if (item.path.isNotBlank()) File(item.path) else null
        var renamedPath = item.path

        val canonicalUri = if (item.isVideo) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)
        }

        var updatedInMediaStore = false
        if (item.id > 0) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            }
            updatedInMediaStore = runCatching {
                context.contentResolver.update(canonicalUri, values, null, null) > 0
            }.getOrDefault(false)
        }

        val customTitle = libraryPreferences.getCustomTitle(item.id)
        val updatedTitle = customTitle ?: finalName.substringBeforeLast('.')

        if (updatedInMediaStore) {
            if (srcFile != null && srcFile.parentFile != null) {
                val destFile = File(srcFile.parentFile, finalName)
                renamedPath = destFile.absolutePath
                verifiedPathsCache.remove(item.path)
                verifiedPathsCache[renamedPath] = System.currentTimeMillis()
                android.media.MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            }
            return@withContext item.copy(name = finalName, path = renamedPath, title = updatedTitle)
        }

        // Direct filesystem rename fallback (for Android <= 28 or full storage access)
        if (srcFile != null && srcFile.exists()) {
            val destFile = File(srcFile.parentFile, finalName)
            val lastModified = srcFile.lastModified()
            val renamed = runCatching { srcFile.renameTo(destFile) }.getOrDefault(false)
            if (renamed) {
                if (lastModified > 0) destFile.setLastModified(lastModified)
                renamedPath = destFile.absolutePath
                verifiedPathsCache.remove(item.path)
                verifiedPathsCache[renamedPath] = System.currentTimeMillis()

                if (item.id > 0) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                        if (android.os.Build.VERSION.SDK_INT <= 28) {
                            put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                        }
                    }
                    runCatching { context.contentResolver.update(canonicalUri, values, null, null) }
                }
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath, srcFile.absolutePath),
                    null,
                    null
                )
                return@withContext item.copy(name = finalName, path = renamedPath, title = updatedTitle)
            }
        }

        null
    }

    private fun saveSnapshot(media: List<MediaImage>) {
        var stream: java.io.FileOutputStream? = null
        runCatching {
            stream = snapshot.startWrite()
            val output = DataOutputStream(stream!!.buffered())
            output.writeInt(2); output.writeInt(media.size)
            media.forEach { item ->
                output.writeLong(item.id); output.writeBoolean(item.isVideo); output.writeUTF(item.name.take(8_000))
                output.writeLong(item.dateTaken); output.writeInt(item.width); output.writeInt(item.height)
                output.writeUTF(item.path.take(16_000)); output.writeLong(item.bucketId); output.writeUTF(item.bucketName.take(8_000))
                output.writeLong(item.durationMs); output.writeUTF(item.mimeType.take(1_000)); output.writeLong(item.sizeBytes)
                output.writeInt(item.orientation); output.writeUTF(item.title.take(8_000))
            }
            output.flush()
            snapshot.finishWrite(stream)
        }.onFailure { snapshot.failWrite(stream) }
    }
}
