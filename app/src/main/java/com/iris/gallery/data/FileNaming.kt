package com.iris.gallery.data

import java.io.File

/**
 * Restores always land on a fresh name so an existing file at the original
 * path can never be overwritten by a trash or vault copy.
 */
fun uniqueDestinationFile(dir: File, fileName: String): File {
    val candidate = File(dir, fileName)
    if (!candidate.exists()) return candidate
    val base = fileName.substringBeforeLast('.', fileName)
    val extension = fileName.substringAfterLast('.', "")
    var index = 1
    while (true) {
        val name = if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension"
        val next = File(dir, name)
        if (!next.exists()) return next
        index++
    }
}
