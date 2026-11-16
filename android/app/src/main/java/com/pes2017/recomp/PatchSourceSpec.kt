package com.pes2017.recomp

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pes2017.recomp.patch.PatchSource
import com.pes2017.recomp.patch.PatchSources
import java.io.File

/**
 * What the import wizard picked, in a form the foreground service can reopen.
 *
 * The service is in the same app but is a different component; a SAF grant is
 * taken persistable by the activity before this is sent. The password is only
 * an Intent extra for the lifetime of the install and is never written to disk.
 */
internal data class PatchSourceSpec(
    val kind: Kind,
    val path: String? = null,
    val uri: Uri? = null,
    val tree: Uri? = null,
    val displayName: String,
    val password: String? = null,
) {

    enum class Kind { ARCHIVE, TREE, PATH }

    fun open(context: Context): PatchSource = when (kind) {
        Kind.ARCHIVE -> PatchSources.fromDocument(
            context,
            uri ?: throw IllegalArgumentException("archive source has no URI"),
            displayName,
            password,
        )

        Kind.TREE -> PatchSources.fromTree(
            context,
            tree ?: throw IllegalArgumentException("folder source has no tree URI"),
            displayName,
            password,
        )

        Kind.PATH -> PatchSources.fromFile(
            File(path ?: throw IllegalArgumentException("path source has no path")),
            password,
        )
    }

    fun put(intent: Intent): Intent {
        intent.putExtra(EXTRA_KIND, kind.name)
        path?.let { intent.putExtra(EXTRA_PATH, it) }
        uri?.let { intent.putExtra(EXTRA_URI, it) }
        tree?.let { intent.putExtra(EXTRA_TREE, it) }
        intent.putExtra(EXTRA_NAME, displayName)
        password?.takeIf { it.isNotEmpty() }?.let { intent.putExtra(EXTRA_PASSWORD, it) }
        return intent
    }

    companion object {
        const val EXTRA_KIND = "patch_source.kind"
        const val EXTRA_PATH = "patch_source.path"
        const val EXTRA_URI = "patch_source.uri"
        const val EXTRA_TREE = "patch_source.tree"
        const val EXTRA_NAME = "patch_source.name"
        const val EXTRA_PASSWORD = "patch_source.password"

        @Suppress("DEPRECATION")
        fun from(intent: Intent): PatchSourceSpec? {
            val kind = runCatching {
                Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: return null)
            }.getOrNull() ?: return null
            return PatchSourceSpec(
                kind = kind,
                path = intent.getStringExtra(EXTRA_PATH),
                uri = intent.getParcelableExtra(EXTRA_URI),
                tree = intent.getParcelableExtra(EXTRA_TREE),
                displayName = intent.getStringExtra(EXTRA_NAME) ?: "PATCH",
                password = intent.getStringExtra(EXTRA_PASSWORD),
            )
        }
    }
}
