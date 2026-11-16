#pragma once
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <mutex>
#include <string>
#include "diagnostics.h"
// Minimal logging. LOGK is for the lines that trace what the title asked of the
// kernel or the GPU, "[k]" so they can be told from the rest.
//
// Every line says when it was written, so it can be matched with a screen
// recording, with logcat, or with another file written at the same moment:
//
//     2026-11-12T07:03:22.123Z 123456.789 [i] what the caller said
//
// The first is UTC with milliseconds; the second is the device's uptime, which
// is what Android's SystemClock.elapsedRealtime() reports, so a line here and
// a line in logcat line up without a clock offset between them. Nothing sits
// between the "[i] " marker and the message, so a line is still found by the
// message it starts with (launcher/report.cpp searches for "] online:" and
// "] report:"). The prefix is formatted into a buffer that is reused, and the
// date half of it only once a second.
//
// A run that is not tracing emits a few hundred lines, but the traces,
// PES17_LOG_ALL=1 and a title that logs one thing a thousand times emit orders
// of magnitude more, which is why the prefix has to be cheap and why a line
// that repeats is counted rather than written again.
//
// Configure with -DPES17_LOGGING=OFF to compile every call below to nothing,
// warnings included; -DPES17_DIAGNOSTICS=OFF is what removes the traces and
// reports.
//
// The disabled form keeps the arguments inside an `if (false)`, so they are
// still type-checked and still count as used, and no expression in a log call
// may have a side effect the program needs.
//
// A line that repeats is written once and then counted rather than written
// again (Repeat, below). The runs worth reading are the ones that log one thing
// a thousand times -- a texture that will not restage, an opcode the command
// processor does not implement -- and in a log where that line is written a
// thousand times the line that says what actually happened is unfindable.
// PES17_LOG_ALL=1 writes every line as it comes, for when the count is not enough.
namespace pes17log
{
    // "2026-11-12T07:03:22.123Z": UTC, never local time, so two files written
    // in different timezones can still be compared line by line. The date is
    // formatted once a second per thread because gmtime is the only expensive
    // part of writing a line, and the milliseconds are cheap.
    inline const char* UtcStamp()
    {
        thread_local char text[32];
        thread_local long long second = -1;
        thread_local size_t dateLength = 0;

        timespec now{};
        clock_gettime(CLOCK_REALTIME, &now);
        if (now.tv_sec != second)
        {
            second = now.tv_sec;
            std::tm utc{};
            gmtime_r(&now.tv_sec, &utc);
            dateLength = size_t(std::snprintf(text, sizeof text, "%04d-%02d-%02dT%02d:%02d:%02d",
                                              utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday,
                                              utc.tm_hour, utc.tm_min, utc.tm_sec));
        }
        std::snprintf(text + dateLength, sizeof text - dateLength, ".%03dZ", int(now.tv_nsec / 1000000));
        return text;
    }

    // The uptime in seconds with three decimals: CLOCK_BOOTTIME on Android and
    // Linux, which counts the time the device was asleep, as elapsedRealtime
    // does. A platform that does not have it gets the plain monotonic clock,
    // which is the same number minus the sleeps.
    inline const char* BootText()
    {
        thread_local char text[24];
        timespec up{};
#if defined(CLOCK_BOOTTIME)
        clock_gettime(CLOCK_BOOTTIME, &up);
#else
        clock_gettime(CLOCK_MONOTONIC, &up);
#endif
        std::snprintf(text, sizeof text, "%lld.%03ld", (long long)up.tv_sec, long(up.tv_nsec / 1000000));
        return text;
    }

    // What every line begins with, the message behind it left alone.
    inline const char* Stamp()
    {
        thread_local char text[64];
        std::snprintf(text, sizeof text, "%s %s ", UtcStamp(), BootText());
        return text;
    }

    // "[w] what the caller said", in the calling thread's own buffer and of any
    // length: the longer messages here (a pipeline's state, a texture's) are the
    // ones somebody is reading precisely because something went wrong with them,
    // and truncating one cuts the part that was wanted. Never null, so a caller
    // that formats and then counts does not have to test it.
    inline const std::string& Line(char level, const char* format, va_list arguments)
    {
        thread_local std::string text;
        char prefix[8];
        const int prefixLength = std::snprintf(prefix, sizeof prefix, "[%c] ", level);
        const size_t at = prefixLength > 0 ? size_t(prefixLength) : 0;
        text.assign(prefix, at);

        size_t room = 256;
        for (;;)
        {
            text.resize(at + room);
            va_list copy;
            va_copy(copy, arguments);
            const int written = std::vsnprintf(text.data() + at, room, format, copy);
            va_end(copy);
            if (written < 0) { text.resize(at); return text; }
            if (size_t(written) < room) { text.resize(at + size_t(written)); return text; }
            room = size_t(written) + 1;
        }
    }

    // The line with its numbers taken out: what two lines that differ only in an
    // address, a size or a shader hash have in common. Those two are one thing
    // happening twice, which is why the count is kept against this and not
    // against the line itself -- a texture failing on four hundred different
    // addresses is otherwise four hundred different lines.
    inline std::string Shape(const std::string& line)
    {
        std::string out;
        out.reserve(line.size());
        for (size_t at = 0; at < line.size();)
        {
            const unsigned char c = (unsigned char)line[at];

            // One run of hexadecimal with a digit in it: a number, however it
            // was written. Without a digit it is a word spelled in a-f -- "e",
            // "fence", "beef", "decade" -- and words are what tells one message
            // from another, so they stay. A single digit counts: "handle 1" and
            // "handle 2" are the same message twice, and reading them as two is
            // how the first version of this left the first nine lines of a
            // repetition unfolded.
            //
            // Hex and decimal are one rule rather than two, because the same
            // number arrives both ways and splitting them is how the second
            // version missed %08X: it read "08A00000" as a two-digit decimal
            // followed by a word, so four hundred addresses were four hundred
            // different lines. "0x1F00" comes out as "#x#" by itself, the x
            // being a character this does not eat.
            if (std::isxdigit(c))
            {
                size_t end = at;
                bool digit = std::isdigit(c) != 0;
                while (end < line.size() && std::isxdigit((unsigned char)line[end]))
                {
                    if (std::isdigit((unsigned char)line[end])) digit = true;
                    end++;
                }
                if (digit) out += '#';
                else out.append(line, at, end - at);
                at = end;
                continue;
            }
            out += char(c);
            at++;
        }
        return out;
    }

    // One kind of line: the shape that matches it, one line of it as it was
    // first written (the flush at the end has to print something), and how far
    // its count has got and how much of that has already been said.
    struct RepeatKind
    {
        std::string shape;
        std::string line;
        char level = 'i';
        uint64_t hits = 0;
        uint64_t written = 0;   // the largest count already beside a written line
    };

    // The repeat table, reachable from FlushRepeats as well as from Repeat.
    struct RepeatTable
    {
        std::mutex lock;
        RepeatKind kinds[512];
        size_t used = 0;
        uint64_t suppressed = 0;
        std::atomic<int> warning{0};   // the count to name in the table-full line, 0 when none
    };

    inline RepeatTable& Repeats()
    {
        static RepeatTable table;
        return table;
    }

    // Whether this line is one to write, and what to put after it: "" the first
    // time, "  (×412)" on one of the doublings, and null for one of the hundreds
    // that are being counted instead. A repeat is therefore visible as a repeat,
    // with the size it had reached beside it, for the price of twice the
    // logarithm of the lines it replaces.
    //
    // The count written is the largest of the doublings a repetition has reached,
    // so it is never above the truth and within a factor of two of it. That is
    // what saying four hundred happened costs without writing four hundred lines;
    // PES17_LOG_ALL=1 writes the four hundred.
    //
    // Nothing here ends a repetition when the lines of it stop. The first version
    // did, and the benchmark of interleaved messages -- texture, kernel, gpu,
    // texture -- showed what that costs: three kinds taking turns end each other's
    // repetitions on every line, so a quarter of a million calls wrote a quarter of
    // a million lines. A repetition is a repetition whether or not something else
    // was logged between its lines.
    inline const char* Repeat(const std::string& line)
    {
        static const bool all = env::Flag("PES17_LOG_ALL");
        if (all) return "";

        // One entry per kind of line. Five hundred and twelve kinds, and then
        // the table stops growing: past that a log is not one where counting
        // helps, and growing it further would cost every call a longer walk
        // under the lock. A kind that does not fit is dropped rather than
        // written every time it comes -- writing them is what the table exists
        // to prevent -- and counted for the one line that says so.
        RepeatTable& table = Repeats();
        const std::string shape = Shape(line);

        std::lock_guard guard(table.lock);
        for (size_t i = 0; i < table.used; i++)
        {
            RepeatKind& kind = table.kinds[i];
            if (kind.shape != shape) continue;
            const uint64_t hits = ++kind.hits;
            // 2nd, 4th, 8th, 16th ... and nothing between them.
            if ((hits & (hits - 1)) != 0) return nullptr;
            kind.written = hits;
            thread_local char suffix[32];
            std::snprintf(suffix, sizeof suffix, "  (×%llu)", (unsigned long long)hits);
            return suffix;
        }
        if (table.used < sizeof table.kinds / sizeof table.kinds[0])
        {
            RepeatKind& kind = table.kinds[table.used++];
            kind.shape = shape;
            kind.line = line;
            kind.level = line.size() > 1 && line[0] == '[' ? line[1] : 'i';
            kind.hits = 1;
            kind.written = 1;   // it is written now, with no count
            return "";
        }
        // Full. The first kind that does not fit is the one the warning is
        // taken for; the counter goes on rising but the line is written once,
        // a line per suppressed kind being the noise the table is for.
        const int suppressed = int(++table.suppressed);
        if (suppressed == 1) table.warning.store(suppressed);
        return nullptr;
    }

    // The table-full warning, if one is due, and the number of kinds it names.
    inline int TakeRepeatWarning()
    {
        return Repeats().warning.exchange(0);
    }

    // An already-formatted "[w] ..." line with the time in front of it, written
    // straight to the log rather than through Repeat: the two callers are the
    // repeat table's own account of itself, and counting those would be an
    // argument with the lock it is already holding.
#ifdef PES17_ANDROID
    void RawLine(char level, const char* text);   // runtime/android/log.cpp
#else
    inline void RawLine(char, const char* text)
    {
        std::fprintf(stderr, "%s%s\n", Stamp(), text);
    }
#endif

    inline void WarnTableFull(int kinds)
    {
        char text[96];
        const int length = std::snprintf(text, sizeof text, "[w] log: repeat table full, %d kinds suppressed", kinds);
        if (length <= 0) return;
        std::string line(text, size_t(length));
        RawLine('w', line.c_str());
    }

    // The counts a run never got to write, one line each: what was counted
    // rather than written, with the total it reached. Called when the log is
    // opened, before that file is closed or turned over, because a count that
    // lands in a file nobody reads again is a count nobody has. Never from a
    // signal handler: it takes the table's lock and the log's.
    inline void FlushRepeats()
    {
        static const bool all = env::Flag("PES17_LOG_ALL");
        if (all) return;   // nothing was counted

        RepeatTable& table = Repeats();
        std::lock_guard guard(table.lock);
        for (size_t i = 0; i < table.used; i++)
        {
            RepeatKind& kind = table.kinds[i];
            if (kind.hits <= kind.written) continue;
            kind.written = kind.hits;
            char suffix[32];
            std::snprintf(suffix, sizeof suffix, "  (×%llu)", (unsigned long long)kind.hits);
            std::string line = kind.line + suffix;
            RawLine(kind.level, line.c_str());
        }
    }
}

// Android has no stderr to write to: a process's output goes nowhere unless
// the device was set up to keep it. The lines go to logcat and to the app's
// own log screen instead (runtime/android/log.cpp), through one function
// rather than the macro's format string, so the level survives.
#ifdef PES17_ANDROID
namespace pes17log
{
    void AndroidWrite(char level, const char* format, ...) __attribute__((format(printf, 2, 3)));
}
#else
namespace pes17log
{
    // The line, and the count of its repeats, to stderr. A function rather than
    // a macro so that the format is still checked and the buffer it formats into
    // can grow; see Line above.
    inline void Write(char level, const char* format, ...) __attribute__((format(printf, 2, 3)));
    inline void Write(char level, const char* format, ...)
    {
        va_list arguments;
        va_start(arguments, format);
        const std::string& line = Line(level, format, arguments);
        va_end(arguments);
        const char* suffix = Repeat(line);
        if (const int kinds = TakeRepeatWarning())
            WarnTableFull(kinds);
        if (suffix)
            std::fprintf(stderr, "%s%s%s\n", Stamp(), line.c_str(), suffix);
    }
}
#endif

#if defined(PES17_NO_LOGGING)
#   define PES17_LOG_AT(level, fmt, ...) \
        do { if (false) std::fprintf(stderr, fmt "\n", ##__VA_ARGS__); } while (false)
#elif defined(PES17_ANDROID)
#   define PES17_LOG_AT(level, fmt, ...) pes17log::AndroidWrite(level, fmt, ##__VA_ARGS__)
#else
#   define PES17_LOG_AT(level, fmt, ...) pes17log::Write(level, fmt, ##__VA_ARGS__)
#endif

#define LOGI(fmt, ...) PES17_LOG_AT('i', fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) PES17_LOG_AT('w', fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) PES17_LOG_AT('E', fmt, ##__VA_ARGS__)
#define LOGK(fmt, ...) PES17_LOG_AT('k', fmt, ##__VA_ARGS__)
