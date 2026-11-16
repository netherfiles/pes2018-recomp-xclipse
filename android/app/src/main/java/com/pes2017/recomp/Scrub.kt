package com.pes2017.recomp

import android.content.Context
import android.os.Environment

/**
 * The diagnostics ZIP without what says who the player is.
 *
 * The same idea as the desktop launcher's report.cpp, and deliberately the same
 * shape: a private address keeps its first bytes because which range it is in
 * is what tells a real connection from one a VPN or a virtual machine added,
 * and the rest of it says nothing about whose router handed it out.
 *
 * Applied line by line while a file is copied, so no log is ever held in
 * memory, and to the report itself, so a path a developer can read does not
 * become a path the player shared by accident.
 *
 * Every rule is deliberately narrow: a report that mangles the evidence is
 * worse than one that redacts a little less. `std::vector` keeps its `::`, a
 * clock keeps its colons, and a shader hash -- a bare 16-hex number -- is left
 * alone because a crash trace full of `<id>` explains nothing.
 */
object Scrub {

    /** The two roots the app's own paths use, and the report's names for them. */
    private const val APP = "<app>"
    private const val GAME = "<game>"
    private const val HOME = "<home>"
    private const val ADDRESS = "<address>"

    fun text(context: Context, text: String): String =
        text(text, context.packageName, gameFolder(context), homeFolder())

    /**
     * The same rules without a Context, which is what a plain JVM test can
     * call: the four inputs are all the rules depend on.
     */
    fun text(text: String, packageName: String, gameFolder: String, homeFolder: String): String {
        if (text.isEmpty()) return text
        var out = text
        // Paths first: they are the longest match, and a home replacement before
        // the game folder one would eat the game folder's own prefix.
        if (out.contains("/storage") || out.contains("/data/")) {
            out = paths(out, packageName, gameFolder, homeFolder)
        }
        out = USER_ID.replace(out) { match -> match.groupValues[1] + "<id>" }
        out = AS_QUOTED.replace(out, "as \"<name>\"")
        // An unquoted name is only a name on the line that names it. Rewriting
        // every "as Foo" in a log would make prose unreadable and hide the very
        // sentence that explains the failure.
        if (out.contains("signin", ignoreCase = true) || out.contains("sign in", ignoreCase = true)) {
            out = AS_WORD.replace(out, "as \"<name>\"")
        }
        out = CONNECT.replace(out, "\"connect <removed>\"")
        out = MAC.replace(out, ADDRESS)
        out = IPV6.replace(out) { match -> if (looksLikeV6(match.value)) ADDRESS else match.value }
        out = IPV4.replace(out) { match -> ipv4(match.value) }
        return out
    }

    /** What the manifest and the report's Evidence section say was removed. */
    fun notes(context: Context): List<String> {
        val out = ArrayList<String>(8)
        out += "IPv4: 10.x.x.x, 192.168.x.x, 172.16-31.x.x and 169.254.x.x keep their shape; " +
            "any other address becomes $ADDRESS"
        out += "IPv6 and MAC addresses become $ADDRESS"
        out += "a player name after \"as\" becomes \"<name>\""
        out += "a quoted \"connect ...\" becomes \"connect <removed>\""
        out += "a 16-hex user or mpdata id after u:, mpdata_ or user/ becomes <id>"
        out += "/data/user/0/<package> and /storage/emulated/0/Android/data/<package> become $APP"
        if (gameFolder(context).isNotEmpty()) out += "the game folder path becomes $GAME"
        out += "the shared storage root becomes $HOME"
        return out
    }

    // ---- paths -------------------------------------------------------------

    private fun paths(text: String, pkg: String, game: String, home: String): String {
        var out = text
        if (pkg.isNotEmpty()) {
            out = out.replace("/data/user/0/$pkg", APP)
            out = out.replace("/storage/emulated/0/Android/data/$pkg", APP)
        }
        if (game.isNotEmpty() && out.contains(game)) out = out.replace(game, GAME)
        if (home.isNotEmpty() && out.contains(home)) out = out.replace(home, HOME)
        return out
    }

    private fun gameFolder(context: Context): String =
        runCatching { Prefs(context).gameDirectory.trim() }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun homeFolder(): String = runCatching {
        Environment.getExternalStorageDirectory()?.absolutePath.orEmpty()
    }.getOrDefault("")

    // ---- addresses ---------------------------------------------------------

    /**
     * The shape of a private address, kept; anything else is somebody's public
     * address and goes entirely. 127 is left to the generic rule: it names the
     * phone itself, and a report that says `<address>` there loses nothing.
     */
    private fun ipv4(address: String): String {
        val parts = address.split('.')
        if (parts.size != 4) return ADDRESS
        val first = parts[0].toIntOrNull() ?: return ADDRESS
        val second = parts[1].toIntOrNull() ?: return ADDRESS
        return when {
            first == 10 -> "10.x.x.x"
            first == 192 && second == 168 -> "192.168.x.x"
            first == 172 && second in 16..31 -> "172.$second.x.x"
            first == 169 && second == 254 -> "169.254.x.x"
            else -> ADDRESS
        }
    }

    /**
     * A candidate that is really an address and not a clock. IPv6 is written in
     * the same characters as `10:00:00`, and the timestamps are the spine of
     * every section of the report, so a candidate with a clock's shape stays.
     */
    private fun looksLikeV6(value: String): Boolean {
        if (CLOCK.matches(value)) return false
        if (value.count { it == ':' } < 2) return false
        // `d::` out of `std::vector` has one hex digit and nothing else.
        return value.any { it.isDigit() } || value.count { it.isLetterOrDigit() } >= 8
    }

    private val CLOCK = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?")
    private val MAC = Regex("(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])")
    private val IPV6 = Regex(
        "(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:]|\\.\\d)"
    )
    private val IPV4 = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")

    // ---- names and ids -----------------------------------------------------

    private val AS_QUOTED = Regex("\\bas\\s+\"[^\"\\n]*\"")
    private val AS_WORD = Regex("\\bas\\s+([^\\s,\";\\])]+)")
    private val CONNECT = Regex("\"[^\"\\n]*\\bconnect\\b[^\"\\n]*\"")
    private val USER_ID = Regex("(u:|mpdata_|user[/\\\\])([0-9a-fA-F]{16})\\b")
}
