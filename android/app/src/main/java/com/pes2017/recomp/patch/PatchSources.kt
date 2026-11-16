package com.pes2017.recomp.patch

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Builds a [PatchSource] from the three things the import wizard offers:
 * an archive document, a folder chosen through SAF (extracted Content tree or
 * a multi-part archive), or a real file/folder path.
 *
 * The password belongs to the source, not to the scanner: libarchive needs it
 * before the first entry is read.
 */
object PatchSources {

    fun fromFile(file: File, password: String? = null): PatchSource =
        if (file.isDirectory) {
            FolderSource(file)
        } else {
            ArchiveSource.Factory.fromFile(null, file, file.name, password)
        }

    fun fromDocument(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        password: String? = null,
    ): PatchSource = ArchiveSource.Factory.fromDocument(context, uri, displayName, password)

    fun fromUris(
        context: Context,
        uris: List<Uri>,
        displayName: String,
        password: String? = null,
    ): PatchSource = ArchiveSource.Factory.fromUris(context, uris, displayName, password)

    /**
     * A SAF tree is one of:
     *  - an extracted Content tree (a Content directory, or the Content
     *    directory itself), scanned by [FolderSource] / [DocumentFolderSource];
     *  - a folder containing one archive or all parts of one multi-part RAR,
     *    scanned by [ArchiveSource];
     *  - anything else, left as a folder so the scanner can report a wrong
     *    title instead of guessing.
     */
    fun fromTree(
        context: Context,
        treeUri: Uri,
        displayName: String,
        password: String? = null,
    ): PatchSource {
        val real = resolveRealDirectory(context, treeUri)
        if (real != null && real.canRead() && real.listFiles() != null) {
            if (hasContentDirectory(real)) return FolderSource(real)
            val archive = findArchiveGroup(real)
            if (archive != null) {
                return ArchiveSource.Factory.fromFiles(
                    context,
                    archive,
                    displayName.ifBlank { real.name },
                    password,
                )
            }
            return FolderSource(real)
        }

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw PatchSourceException(
                "cannot open folder: $treeUri",
                PatchSourceFailure.IO,
            )
        val children = try {
            root.listFiles().toList()
        } catch (t: Throwable) {
            throw PatchSourceException(
                "cannot list $treeUri: ${t.message}",
                PatchSourceFailure.IO,
                t,
            )
        }
        val label = displayName.ifBlank { root.name ?: "PATCH" }
        if (root.name.equals("Content", ignoreCase = true) ||
            children.any {
                it.isDirectory && it.name?.equals("Content", ignoreCase = true) == true
            } ||
            children.any {
                it.isDirectory && it.name?.equals("4A3007D3", ignoreCase = true) == true
            }
        ) {
            return DocumentFolderSource(context, treeUri, label)
        }

        val archiveNames = choosePartGroup(children.mapNotNull { it.name })
        if (archiveNames != null) {
            val byName = children.filter { it.name != null }.groupBy { it.name }
            val documents = archiveNames.mapNotNull { name -> byName[name]?.singleOrNull() }
            if (documents.size == archiveNames.size) {
                return ArchiveSource.Factory.fromDocumentFiles(
                    context,
                    documents,
                    label,
                    password,
                )
            }
        }
        return DocumentFolderSource(context, treeUri, label)
    }
}
