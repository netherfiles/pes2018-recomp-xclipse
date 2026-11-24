package com.pes2017.recomp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * What the build is and where it comes from: the version on this phone, the
 * repository, and the licence the app ships under, read from assets/LICENSE.
 * Reached from Settings, so the launcher stays one screen.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        applySystemBars()
        title = getString(R.string.about_title)

        findViewById<View>(R.id.about_source_link).setOnClickListener { openSource() }

        val source = sourceUrl()
        findViewById<TextView>(R.id.about_source_link).text = source

        val version = findViewById<TextView>(R.id.about_version)
        // The build line of the launcher: the version name already carries the
        // commit it was built from, and the code is what Android compares.
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else info.versionCode
            version.text = getString(R.string.version_tag, info.versionName ?: "", code)
        } catch (_: Throwable) {
        }

        val license = findViewById<TextView>(R.id.about_license_text)
        // A licence text is tens of kilobytes: read it off the main thread, and
        // say so rather than sit blank if the asset cannot be opened.
        thread(name = "about-license", isDaemon = true) {
            val text = try {
                assets.open(LICENSE_ASSET).bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                ""
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                license.text = text.ifBlank {
                    "GPL-3.0-only — the full licence text could not be read from this build."
                }
            }
        }
    }

    private fun openSource() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl())))
        } catch (_: Throwable) {
            // A phone with no browser to open it: the URL is still on screen.
        }
    }

    /**
     * The repository at the exact commit this apk was built from. BuildConfig
     * carries it, and the version name carries the same string in parentheses;
     * a local build has no commit, so the repository root is the honest link.
     */
    private fun sourceUrl(): String {
        val sha = BuildConfig.BUILD_SHA.trim().takeIf { it.isNotEmpty() && it != "local" }
            ?: versionNameSha()
        return if (sha == null) SOURCE_REPO else "$SOURCE_REPO/tree/$sha"
    }

    private fun versionNameSha(): String? = try {
        val name = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        Regex("\\(([^)]+)\\)").find(name)?.groupValues?.get(1)
            ?.trim()?.takeIf { it.isNotEmpty() && it != "local" }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val SOURCE_REPO = "https://github.com/sharifhidayatulloh/pes2018-recomp"
        private const val LICENSE_ASSET = "LICENSE"
    }
}
