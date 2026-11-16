// The log on Android: logcat, and a file when the app asks for one.
//
// Nothing reads a process's stderr on Android -- it goes to /dev/null unless
// the device is set up for it -- so the log lines the desktop build prints
// have to be handed to the platform's logger instead. The app shows the tail
// of the same lines on its own screen, which is what a player attaches to a
// bug report.
#ifdef PES17_ANDROID

#include "android.h"
#include "../log.h"

#include <android/api-level.h>
#include <android/log.h>

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <pthread.h>
#include <string>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace
{
    constexpr const char* kTag = "pes17";
    constexpr size_t kKeptLines = 400;     // what the app's log screen shows
    constexpr long long kMaxLogBytes = 4LL * 1024 * 1024;   // one run's file, before it is turned over

    std::mutex g_lock;
    std::FILE* g_file = nullptr;
    std::FILE* g_fileExt = nullptr;
    std::deque<std::string> g_tail;
    std::string g_status;

    const char* AbiName()
    {
#if defined(__aarch64__)
        return "arm64-v8a";
#elif defined(__arm__)
        return "armeabi-v7a";
#elif defined(__x86_64__)
        return "x86_64";
#elif defined(__i386__)
        return "x86";
#else
        return "unknown";
#endif
    }

    // Which of the app's processes this is. The run's name ends in :game; the
    // launcher's does not. Read the same way jni.cpp names the file.
    const char* ProcessName()
    {
        if (std::FILE* cmdline = std::fopen("/proc/self/cmdline", "rb"))
        {
            char name[256] = {};
            const size_t read = std::fread(name, 1, sizeof(name) - 1, cmdline);
            std::fclose(cmdline);
            if (read > 0 && std::strstr(name, ":game")) return "game";
        }
        return "launcher";
    }

    std::string DeviceModel()
    {
        char model[PROP_VALUE_MAX] = {};
        if (__system_property_get("ro.product.model", model) <= 0 || !*model)
            return "unknown";
        return model;
    }

    // One line at the top of each file, saying what wrote it. Both copies get
    // this same string, so the two files of one run carry the same instant and
    // can be told apart from the run before.
    //
    // It still begins with "===== new run" because the tools grep for that to
    // find where a run starts.
    std::string RunHeader()
    {
        const int api = android_get_device_api_level();
        std::string header = "===== new run ";
        header += pes17log::UtcStamp();
        header += " boot=";
        header += pes17log::BootText();
        header += " pid=" + std::to_string(int(getpid()));
        header += " proc=";
        header += ProcessName();
        header += " abi=";
        header += AbiName();
        header += " device=" + DeviceModel();
        header += " api=" + std::to_string(api > 0 ? api : 0);
        header += " =====";
        return header;
    }

    // A file past its limit is kept as `<path>.1` rather than deleted: what a
    // bug report is after is usually the run that just ended, and deleting it
    // at the next start throws exactly that away. One generation is enough for
    // a phone. Never mid-run -- the check is at open, when nothing holds the
    // handle -- so a reader sees one whole file and one whole predecessor.
    void RotateIfLarge(const char* path, std::string& warning)
    {
        struct stat info {};
        if (::stat(path, &info) != 0 || info.st_size <= kMaxLogBytes) return;

        const std::string previous = std::string(path) + ".1";
        std::remove(previous.c_str());   // only one generation is kept
        if (std::rename(path, previous.c_str()) == 0) return;

        // FUSE can refuse a rename. The file still has to go, or the next run
        // appends to a log nobody can open, so it is removed and said once.
        std::remove(path);
        if (!warning.empty()) warning += "; ";
        warning += std::string("android: could not rotate ") + path + ", removed it";
    }

    std::FILE* OpenSingleLog(const char* path, const char* header, std::string& warning)
    {
        if (!path || !*path) return nullptr;
        RotateIfLarge(path, warning);
        std::FILE* f = std::fopen(path, "a");
        if (!f) return nullptr;
        std::setvbuf(f, nullptr, _IOLBF, 0);
        std::fputs("\n", f);
        std::fputs(header, f);
        std::fputs("\n", f);
        std::fflush(f);
        return f;
    }
}

void android::OpenLogFile(const char* path)
{
    if (!path || !*path) return;

    // The counts the last run never got to write go into the file it wrote
    // them for, before this call closes or turns that file over.
    pes17log::FlushRepeats();

    const std::string header = RunHeader();
    std::string warning;

    {
        std::lock_guard lock(g_lock);
        if (g_file) { std::fclose(g_file); g_file = nullptr; }
        if (g_fileExt) { std::fclose(g_fileExt); g_fileExt = nullptr; }
        g_tail.clear();

        g_file = OpenSingleLog(path, header.c_str(), warning);

        const auto& ext = android::GetPaths().external;
        if (!ext.empty())
        {
            const char* slash = std::strrchr(path, '/');
            const std::string extPath = ext + "/" + (slash ? slash + 1 : "game.log");
            if (extPath != path)
            {
                g_fileExt = OpenSingleLog(extPath.c_str(), header.c_str(), warning);
            }
        }
    }

    // Outside the lock: the log writer takes it.
    if (!warning.empty()) android::LogLine('w', warning.c_str());
}

// ---- whatever the runtime prints rather than logs -------------------------
//
// crash.cpp writes the guest's backtrace to stderr, as it does on a desktop,
// and so do abort(), assert() and the C++ runtime on an uncaught exception.
// On Android none of that goes anywhere: an app's stdout and stderr are
// /dev/null unless the device has been told otherwise. So the two are
// replaced with a pipe and read back into this log, which is the difference
// between "it crashed" and a stack trace naming the guest function.
namespace
{
    int g_capture[2] = { -1, -1 };

    void* DrainStandardStreams(void*)
    {
        std::string line;
        char buffer[512];
        for (;;)
        {
            const ssize_t got = read(g_capture[0], buffer, sizeof buffer);
            if (got <= 0)
            {
                if (got < 0 && errno == EINTR) continue;
                break;
            }
            for (ssize_t i = 0; i < got; i++)
            {
                if (buffer[i] == '\n')
                {
                    if (!line.empty()) android::LogLine('E', line.c_str());
                    line.clear();
                }
                else if (line.size() < 2000)
                {
                    line.push_back(buffer[i]);
                }
            }
        }
        return nullptr;
    }
}

void android::CaptureStandardStreams()
{
    if (g_capture[0] >= 0) return;
    if (pipe(g_capture) != 0) return;

    // Unbuffered, so a crash handler's last words are not still sitting in
    // a buffer when the process dies.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    std::setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(g_capture[1], STDOUT_FILENO);
    dup2(g_capture[1], STDERR_FILENO);

    pthread_t reader;
    if (pthread_create(&reader, nullptr, &DrainStandardStreams, nullptr) == 0)
    {
        pthread_detach(reader);
    }
}

void android::LogLine(char level, const char* text)
{
    if (!text) return;

    // The time is put on here rather than at each caller: logcat, the file and
    // the copy beside it then carry the same line, and the lines read back
    // from the stderr pipe get it too.
    thread_local std::string stamped;
    stamped.assign(pes17log::Stamp());
    stamped += text;

    const int priority = level == 'E' ? ANDROID_LOG_ERROR
                       : level == 'w' ? ANDROID_LOG_WARN
                                      : ANDROID_LOG_INFO;
    __android_log_write(priority, kTag, stamped.c_str());

    std::lock_guard lock(g_lock);
    if (g_file)
    {
        std::fputs(stamped.c_str(), g_file);
        std::fputc('\n', g_file);
    }
    if (g_fileExt)
    {
        std::fputs(stamped.c_str(), g_fileExt);
        std::fputc('\n', g_fileExt);
    }
    // Warnings and errors are what a player is asked for; the rest of the
    // tail is context around them.
    g_tail.push_back(stamped);
    if (g_tail.size() > kKeptLines) g_tail.pop_front();
}

void android::SetStatus(const char* text)
{
    if (!text) return;
    std::lock_guard lock(g_lock);
    g_status = text;
}

std::string android::Status()
{
    std::lock_guard lock(g_lock);
    return g_status;
}

namespace android::detail
{
    std::string LogTail()
    {
        std::lock_guard lock(g_lock);
        std::string all;
        for (const std::string& line : g_tail) { all += line; all += '\n'; }
        return all;
    }
}

// The printf side, called by the macros in log.h.
namespace pes17log
{
    // The sink the repeat table's own lines go to (see RawLine in log.h): the
    // same file, without going back through Repeat.
    void RawLine(char level, const char* text)
    {
        android::LogLine(level, text);
    }

    void AndroidWrite(char level, const char* format, ...)
    {
        va_list arguments;
        va_start(arguments, format);
        const std::string& line = Line(level, format, arguments);
        va_end(arguments);

        // Counted here against the same rule as the desktop build (log.h,
        // Repeat), because this is the log the app's own screen shows and the
        // player shares: a phone is where a line that repeats four hundred
        // times costs the most to read.
        const char* suffix = Repeat(line);
        if (const int kinds = TakeRepeatWarning())
            WarnTableFull(kinds);
        if (!suffix) return;

        // The body is joined in a buffer of its own so a line that comes every
        // frame does not allocate one each time.
        thread_local std::string body;
        body.assign(line);
        body += suffix;
        android::LogLine(level, body.c_str());
    }
}

#endif  // PES17_ANDROID
