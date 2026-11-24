package com.pes2017.recomp

/**
 * The release feed behind the update check, with no Android in it: the GitHub
 * releases array comes in as text and leaves as a list the activity can use.
 *
 * It reads the JSON itself: the local unit tests run without Android's
 * org.json, which is only a stub there.
 */
object UpdateFeed {

    data class Release(
        val tag: String,
        val code: Int,
        val versionName: String,
        val apkUrl: String,
        val pageUrl: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val notes: String,
        val sha256: String,
    )

    private val VERSION_CODE = Regex("APK versionCode:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val PRE = Regex("^([a-z]*)(\\d*)")

    /** Every release in the feed, with its APK and the build number from its notes. */
    fun parseReleases(json: String): List<Release> {
        val root = try {
            Json(json).read()
        } catch (_: Throwable) {
            return emptyList()   // a feed that will not read is no updates, not a crash
        }
        val items = root as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            anyMap(item)?.let { releaseOf(it) }
        }
    }

    /**
     * The version.json of a release: its build number and the name it carries.
     *
     * Deferred: the release workflow does not publish version.json, so nothing
     * in the app reads this in production yet. It is kept, with its test, for
     * the day a feed carries the pair as data instead of a note in the body;
     * do not treat it as an active part of the update check.
     */
    fun parseVersionJson(json: String): Pair<Int, String>? {
        val root = try {
            Json(json).read()
        } catch (_: Throwable) {
            return null
        }
        val obj = anyMap(root) ?: return null
        val code = (obj["code"] as? Number)?.toInt()
            ?: (obj["code"] as? String)?.toIntOrNull()
            ?: return null
        return code to (obj["versionName"] as? String).orEmpty()
    }

    /**
     * The newest release worth telling the player about, or null. Drafts never
     * count, and a test build only on a phone already carrying one.
     *
     * The unit is chosen for the whole feed, never per pair: when any eligible
     * release carries a build code, only coded releases are considered and the
     * highest code wins; only when none carries one do the version names
     * decide. A build code is never compared with a version name.
     */
    fun pickUpdate(
        releases: List<Release>,
        installedCode: Int,
        installedName: String,
        allowPrerelease: Boolean,
    ): Release? {
        val eligible = releases.filter { release ->
            !release.draft && (allowPrerelease || !release.prerelease)
        }
        if (eligible.isEmpty()) return null
        val coded = eligible.filter { it.code > 0 }
        if (coded.isNotEmpty()) {
            return coded.filter { it.code > installedCode }.maxByOrNull { it.code }
        }
        return newestByName(eligible, installedName)
    }

    /** Whether [candidate] names a version past [installed], by name alone. */
    fun isNewerName(installed: String, candidate: String): Boolean {
        val installedVersion = parseVersionName(installed) ?: return false
        val candidateVersion = parseVersionName(candidate) ?: return false
        return compareVersions(candidateVersion, installedVersion) > 0
    }

    /** The newest of the name-only candidates, or null when none is newer. */
    private fun newestByName(candidates: List<Release>, installedName: String): Release? {
        var best: Release? = null
        var bestVersion: Version? = null
        for (release in candidates) {
            if (!isNewerName(installedName, release.versionName)) continue
            val version = parseVersionName(release.versionName) ?: continue
            if (bestVersion == null || compareVersions(version, bestVersion) > 0) {
                best = release
                bestVersion = version
            }
        }
        return best
    }

    /** A test build (rc/pad/pr) already on the phone may take the next one. */
    fun allowPrerelease(installedName: String): Boolean {
        val token = installedName.trim().substringBefore(' ').lowercase()
        return token.contains("-rc") || token.contains("-pad") || token.contains("-pr")
    }

    private fun releaseOf(obj: Map<String, Any?>): Release? {
        val tag = (obj["tag_name"] as? String)?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val versionName = tag.removePrefix("v").removePrefix("V")
        val body = obj["body"] as? String ?: ""
        val assets = (obj["assets"] as? List<*>)?.mapNotNull { anyMap(it) }.orEmpty()
        fun nameOf(asset: Map<String, Any?>) = (asset["name"] as? String).orEmpty()

        // The APK the workflow names after the tag first, then any declared as
        // an Android package, then the first .apk in the list.
        val preferred = assets.firstOrNull {
            nameOf(it).equals("PES2018-recomp-$tag.apk", ignoreCase = true) ||
                nameOf(it).equals("PES2018-recomp-$versionName.apk", ignoreCase = true)
        }
        val typed = assets.firstOrNull {
            (it["content_type"] as? String)?.contains("package-archive", ignoreCase = true) == true
        }
        val anyApk = assets.firstOrNull { nameOf(it).endsWith(".apk", ignoreCase = true) }
        val apk = preferred ?: typed ?: anyApk
        val digest = (apk?.get("digest") as? String).orEmpty()
        return Release(
            tag = tag,
            code = VERSION_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            versionName = versionName,
            apkUrl = (apk?.get("browser_download_url") as? String).orEmpty(),
            pageUrl = (obj["html_url"] as? String).orEmpty(),
            prerelease = obj["prerelease"] as? Boolean ?: false,
            draft = obj["draft"] as? Boolean ?: false,
            notes = body,
            sha256 = if (digest.startsWith("sha256:", ignoreCase = true)) digest.substring(7) else digest,
        )
    }

    private data class Version(val parts: List<Int>, val pre: String?)

    /** "0.3.2-rc2 (sha)" is the 0.3.2-rc2 token; no suffix means a final build. */
    private fun parseVersionName(name: String): Version? {
        val token = name.trim().substringBefore(' ').removePrefix("v").removePrefix("V")
        val dash = token.indexOf('-')
        val base = if (dash >= 0) token.substring(0, dash) else token
        val pre = if (dash >= 0) token.substring(dash + 1).lowercase().ifEmpty { null } else null
        val parts = base.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.isEmpty()) return null
        return Version(parts, pre)
    }

    /** Part by part, then a final build over a test one, then rc2 over rc1. */
    private fun compareVersions(a: Version, b: Version): Int {
        val count = maxOf(a.parts.size, b.parts.size, 3)
        for (index in 0 until count) {
            val step = a.parts.getOrElse(index) { 0 }.compareTo(b.parts.getOrElse(index) { 0 })
            if (step != 0) return step
        }
        if (a.pre == null && b.pre == null) return 0
        if (a.pre == null) return 1
        if (b.pre == null) return -1
        return comparePrerelease(a.pre, b.pre)
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val ra = PRE.find(a)
        val rb = PRE.find(b)
        val name = (ra?.groupValues?.get(1) ?: a).compareTo(rb?.groupValues?.get(1) ?: b)
        if (name != 0) return name
        val na = ra?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val nb = rb?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return na.compareTo(nb)
    }

    /** The parser builds string-keyed objects; this only drops the star projection. */
    @Suppress("UNCHECKED_CAST")
    private fun anyMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    /** Just enough JSON to walk the feed: objects, arrays, strings, numbers,
     *  booleans and null. Anything else is a feed that will not read. */
    private class Json(private val text: String) {
        private var at = 0

        fun read(): Any? {
            val value = value()
            space()
            if (at != text.length) throw IllegalArgumentException("trailing text")
            return value
        }

        private fun value(): Any? {
            space()
            if (at >= text.length) throw IllegalArgumentException("unexpected end")
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("$c")
            }
        }

        private fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            at++   // {
            space()
            if (at < text.length && text[at] == '}') {
                at++
                return out
            }
            while (true) {
                space()
                val key = string()
                space()
                expect(':')
                out[key] = value()
                space()
                when {
                    at < text.length && text[at] == ',' -> at++
                    at < text.length && text[at] == '}' -> {
                        at++
                        return out
                    }
                    else -> throw IllegalArgumentException("object")
                }
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            at++   // [
            space()
            if (at < text.length && text[at] == ']') {
                at++
                return out
            }
            while (true) {
                out += value()
                space()
                when {
                    at < text.length && text[at] == ',' -> at++
                    at < text.length && text[at] == ']' -> {
                        at++
                        return out
                    }
                    else -> throw IllegalArgumentException("array")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) throw IllegalArgumentException("string")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (at >= text.length) throw IllegalArgumentException("escape")
                        when (val e = text[at++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) throw IllegalArgumentException("unicode")
                                val hex = text.substring(at, at + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("unicode")
                                out.append(hex.toChar())
                                at += 4
                            }
                            else -> throw IllegalArgumentException("escape $e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "-+.eE")) at++
            val body = text.substring(start, at)
            return body.toLongOrNull() ?: body.toDoubleOrNull()
                ?: throw IllegalArgumentException("number")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw IllegalArgumentException(word)
            at += word.length
            return value
        }

        private fun expect(c: Char) {
            if (at >= text.length || text[at] != c) throw IllegalArgumentException("expected $c")
            at++
        }
    }
}
