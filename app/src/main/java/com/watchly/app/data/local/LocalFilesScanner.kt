/*
 * Watchly — enumerates the video files inside a user-granted
 * folder (cycle 5). Uses androidx.documentfile over the SAF tree
 * URI the user picked; recursion is depth-bounded so a huge or
 * cyclic tree cannot hang the Library. Only names + URIs are read
 * here — file bytes are never touched until the player opens one.
 */
package com.watchly.app.data.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object LocalFilesScanner {

    /** Deepest folder nesting the scan will descend. */
    private const val MAX_DEPTH = 6

    /** Display name for one picked document, via the provider. */
    fun displayName(context: Context, uri: Uri): String =
        DocumentFile.fromSingleUri(context, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: uri.toString()

    /**
     * All video files under [treeUri], as (uri, displayName) pairs,
     * sorted by name for a stable Library order. Empty when the
     * grant is gone or the folder holds no videos.
     */
    fun scanTree(context: Context, treeUri: Uri): List<Pair<String, String>> {
        val root = runCatching {
            DocumentFile.fromTreeUri(context, treeUri)
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        walk(root, depth = 0, out = out)
        return out.sortedBy { it.second.lowercase() }
    }

    private fun walk(
        dir: DocumentFile,
        depth: Int,
        out: MutableList<Pair<String, String>>
    ) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            when {
                child.isDirectory -> walk(child, depth + 1, out)
                child.isFile && isVideo(child) -> {
                    val name = child.name ?: continue
                    out.add(child.uri.toString() to name)
                }
            }
        }
    }

    /** Video by provider MIME type, falling back to the extension. */
    private fun isVideo(file: DocumentFile): Boolean {
        val type = file.type
        if (type != null) return type.startsWith("video/")
        val name = file.name ?: return false
        return isVideoFileName(name)
    }
}
