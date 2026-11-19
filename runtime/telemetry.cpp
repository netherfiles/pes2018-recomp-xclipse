// See telemetry.h: a per-second CSV that the log's repeat table cannot give us.
//
// The split is deliberate. The render thread pushes a frame time and a few
// atomics (Frame/Compiles), and everything that could take time -- formatting,
// sorting the percentile ring, /proc, the write -- is done by the writer thread a
// second later. On a phone, a telemetry line that costs a stalled frame is worse
// than no line.
//
// The columns that no thread can measure from here (the ring consumer, the
// presenter, the audio mixer, the texture decode) are written as 0 rather than
// left out: a stable column count is what lets tools/ read the file, and the
// alternative would be a second kind of row. Each of those threads can measure
// itself with RUSAGE_THREAD and hand the total over later; the row already has
// its column.
#include "telemetry.h"

#include "crash.h"
#include "env.h"
#include "frame_rate.h"
#include "gpu/internal.h"          // gpu::detail::g_fenceWaits, the title's fence waits
#include "gpu/vulkan/pipeline.h"   // the device name and whether it samples BC
#include "gpu/vulkan/presenter.h"  // frames the window showed
#include "gpu/vulkan/texture_cache.h"
#include "gpu/vulkan/texture_stats.h"
#include "log.h"
#include "platform.h"
#include "android/audio_stats.h"   // has its own empty form for a desktop build

#ifdef PES17_ANDROID
#include "android/android.h"       // the files folder the CSV lives in
#endif

#include <fcntl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

namespace
{
    using Clock = std::chrono::steady_clock;

    // Ten seconds at 60 frames a second: long enough that a percentile is not a
    // guess, short enough that a scene change leaves the old frames behind. The
    // 172 ms resolve burst in the log this was written for would still be in it.
    constexpr uint32_t kRingFrames = 600;
    // A run's worth of rows is a few hundred KB; past this the file is turned
    // over rather than grown, because it is read on a phone.
    // Without a device there is still a run to record (a headless build, a
    // driver that never came up): the header is written anyway after this many
    // seconds, so the rows are not lost to a name that will never arrive.
    constexpr uint64_t kHeaderWaitSeconds = 5;

#if defined(PES17_VERSION_TU0)
    constexpr const char* kBuild = "tu0";
#else
    constexpr const char* kBuild = "tu6";
#endif

    std::atomic<bool> g_on{ false };
    std::atomic<bool> g_running{ false };
    std::once_flag g_once;
    std::thread g_writer;
    int g_fd = -1;

    // ---- what the render thread publishes ----------------------------------

    // The frame times, and how many have been pushed. One producer (the render
    // thread) and one consumer (the writer), so the slot index needs no atomic:
    // the count's release store publishes the slot it belongs to.
    std::atomic<uint64_t> g_frameTimes[kRingFrames];
    std::atomic<uint64_t> g_frameTimesWritten{ 0 };
    uint32_t g_frameTimesAt = 0;   // render thread only

    // The last second, reset by the writer with exchange.
    std::atomic<uint32_t> g_windowFrames{ 0 };
    std::atomic<uint64_t> g_windowDraws{ 0 };
    std::atomic<uint32_t> g_windowMatchFrames{ 0 };
    std::atomic<uint32_t> g_jank20{ 0 }, g_jank33{ 0 }, g_jank50{ 0 }, g_jank100{ 0 };

    // Cumulative, and diffed by the writer. Compiles never reset; the render
    // thread's CPU time is a snapshot it retakes once a second.
    std::atomic<uint64_t> g_pipelinesLinked{ 0 }, g_shadersBuilt{ 0 };
    std::atomic<uint64_t> g_renderCpuMicroseconds{ 0 };
    std::atomic<int> g_thermal{ 0 };
    Clock::time_point g_lastCpuSample{};

    // What the writer saw last, kept so a monotonic counter becomes a per-second
    // one. Writer thread only.
    struct Previous
    {
        uint64_t presents = 0;
        uint64_t fenceWaitNanoseconds = 0;
        uint64_t processCpuMicroseconds = 0;
        uint64_t renderCpuMicroseconds = 0;
        uint64_t pipelines = 0, shaders = 0;
        uint64_t evictPasses = 0, evictBytes = 0, evictMicroseconds = 0;
        uint64_t decodeMicroseconds = 0, stageMicroseconds = 0, readMicroseconds = 0;
        uint64_t newTextures = 0, restaged = 0, storeHits = 0, storeMisses = 0;
        uint64_t missingFrames = 0, droppedFrames = 0;
    } g_previous;

    // The ring, sorted in place: allocated once (the writer's alone), never a row.
    uint64_t g_samples[kRingFrames];

    // ---- small helpers -----------------------------------------------------

    uint64_t WallMilliseconds()
    {
        timespec now{};
        clock_gettime(CLOCK_REALTIME, &now);
        return uint64_t(now.tv_sec) * 1000 + uint64_t(now.tv_nsec) / 1'000'000;
    }

    // CLOCK_BOOTTIME where there is one, as log.h's stamps use: it is what
    // Android's elapsedRealtime reports, so a row and a logcat line line up.
    uint64_t BootMilliseconds()
    {
        timespec now{};
#if defined(CLOCK_BOOTTIME)
        clock_gettime(CLOCK_BOOTTIME, &now);
#else
        clock_gettime(CLOCK_MONOTONIC, &now);
#endif
        return uint64_t(now.tv_sec) * 1000 + uint64_t(now.tv_nsec) / 1'000'000;
    }

    // A monitor that only moves forward becomes this second's value. A counter
    // that went backwards was reset by someone else (the perf line exchanges
    // the fence waits every 600 swaps); the value since the reset is the best
    // answer there is.
    uint64_t Since(uint64_t& previous, uint64_t now)
    {
        const uint64_t delta = now >= previous ? now - previous : now;
        previous = now;
        return delta;
    }

    // A kilobyte count out of a /proc file: this process's resident set from
    // status, the device's own left-over memory from meminfo. Once a second, on
    // the writer, is all this costs -- and it is one read into a buffer of the
    // stack, not a stdio stream the row has to allocate.
    uint64_t ProcKilobytes(const char* path, const char* key)
    {
        const int fd = open(path, O_RDONLY);
        if (fd < 0) return 0;
        char text[4096];
        const ssize_t got = ::read(fd, text, sizeof text - 1);
        close(fd);
        if (got <= 0) return 0;
        text[got] = 0;

        const char* at = std::strstr(text, key);
        if (!at) return 0;
        at += std::strlen(key);
        while (*at == ':' || *at == ' ' || *at == '\t') at++;
        return std::strtoull(at, nullptr, 10);
    }

    // Nearest rank: the smallest sample at or above this many per cent. What a
    // reader wants from "p95" is the frame time one frame in twenty exceeded.
    uint64_t Percentile(const uint64_t* sorted, uint32_t count, uint32_t percent)
    {
        if (!count) return 0;
        uint64_t rank = (uint64_t(percent) * count + 99) / 100;
        if (rank < 1) rank = 1;
        if (rank > count) rank = count;
        return sorted[rank - 1];
    }

    void WriteAll(const char* text, size_t length)
    {
        if (g_fd < 0) return;
        // The file is opened with O_APPEND and written without a stdio buffer, so
        // a row is in the kernel as it is written: a run that ends on a fault
        // still has every row its writer got to.
        while (length)
        {
            const ssize_t written = write(g_fd, text, length);
            if (written <= 0) return;
            text += written;
            length -= size_t(written);
        }
    }

    bool OpenFile(std::string& path)
    {
        if (const char* chosen = env::Text("PES17_TELEMETRY_PATH")) path = chosen;
        else
        {
#ifdef PES17_ANDROID
            const std::string& files = android::GetPaths().files;
            if (files.empty()) return false;
            path = files + "/telemetry.csv";
#else
            path = "telemetry.csv";
#endif
        }

        // The run before is worth more than the run whose start it predicts: the
        // old file is kept as .1 instead of being appended to or wiped in place.
        // Rotate on every process start, not only past 8 MB: each run must begin
        // with its own # v1 / # resolved / column line, or the exporter's reader
        // maps columns from a previous run's file and the report goes blank.
        struct stat info{};
        if (stat(path.c_str(), &info) == 0 && info.st_size > 0)
            std::rename(path.c_str(), (path + ".1").c_str());

        g_fd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
        return g_fd >= 0;
    }

    // The two lines nothing changes: which build and device this is, and what
    // the app's options resolved to (not what they were asked to be -- the
    // device-sized defaults are in the environment by the time the first frame
    // has run, and the texture cache's budget is the device's own).
    void WriteHeader(uint64_t wallStart, uint64_t bootStart)
    {
        char device[96];
        const char* name = vk::pipeline::DeviceName();
        std::snprintf(device, sizeof device, "%s", name && *name ? name : "none");
        // One token: the rest of the line is space-separated, and this is a name
        // out of a driver ("Adreno (TM) 650").
        for (char* at = device; *at; at++)
            if (*at == ' ' || *at == '(' || *at == ')' || *at == ',') *at = '_';

        char line[512];
        int length = std::snprintf(line, sizeof line, "# v1 %s %s %llu %llu\n", kBuild, device,
                                   (unsigned long long)wallStart, (unsigned long long)bootStart);
        if (length > 0) WriteAll(line, size_t(std::min<int>(length, int(sizeof line) - 1)));

        const char* store = env::Text("PES17_TEXTURE_STORE");
        // The row must say which path actually ran, not which switch the user
        // typed: ASTC is now the default where BC is not enabled, so an unset
        // PES17_BC_ASTC is still "astc" on a Mali phone.
        const bool forceAstc = env::Flag("PES17_BC_ASTC") || env::Flag("PES17_BC1_ASTC");
        const char* bcAstc = env::Flag("PES17_NO_BC_ASTC") ? "off"
                           : forceAstc ? "astc"
                           : vk::pipeline::TextureCompressionBC() ? "hw"
                           : vk::pipeline::AstcSampled() ? "astc"
                           : "off";
        // PES17_HINT=0 is no session at all; with the graphics pipeline session
        // available (Android 16) it is the one the runs were won with, and
        // PES17_HINT_PIPELINE=0 is what makes a plain one.
        const char* hint = "pipeline";
        if (const char* off = env::Text("PES17_HINT"); off && off[0] == '0' && off[1] == 0) hint = "off";
        else if (const char* plain = env::Text("PES17_HINT_PIPELINE"); plain && plain[0] == '0' && plain[1] == 0)
            hint = "on";
        const char* audioBurst = env::Text("PES17_AUDIO_BURST");

        length = std::snprintf(line, sizeof line,
            "# resolved: texture_budget_mb=%llu, bc_astc=%s, store=%s, store_mb=%llu,"
            " msaa=%u, scene_scale=%.2f, fps_limit=%u, hint=%s, audio_burst=%s, arena_mb=%llu\n",
            (unsigned long long)(vk::textures::Budget() >> 20), bcAstc, store ? store : "off",
            (unsigned long long)env::Number("PES17_TEXTURE_STORE_MB", 1024),
            env::Flag("PES17_NO_MSAA") ? 0u : 1u,
            env::Real("PES17_SCENE_SCALE", 1.0), frame_rate::Limit(), hint,
            audioBurst ? audioBurst : "off",
            (unsigned long long)env::Number("PES17_ARENA_MB", 512));
        if (length > 0) WriteAll(line, size_t(std::min<int>(length, int(sizeof line) - 1)));
        // The column names are part of the file, not of this source file: the
        // report and tools map by name, and a tail that starts mid-file still
        // carries them (the exporter prepends the one line).
        static const char* const kColumns =
            "boot_ms,wall_ms,run_s,frames,"
            "ft_p50_us,ft_p95_us,ft_p99_us,ft_max_us,"
            "jank20,jank33,jank50,jank100,"
            "draws,presents,gpu_wait_ms,"
            "cpu_proc_ms,cpu_render_ms,cpu_ring_ms,cpu_present_ms,cpu_audio_ms,cpu_decode_ms,"
            "tex_live_mb,tex_budget_mb,evict_n,evict_mb,evict_us,"
            "decode_ms,stage_ms,read_ms,new_n,restage_n,store_hit,store_miss,"
            "pipe_n,shader_n,au_missing,au_dropped,au_depth_min,au_depth_mean,"
            "thermal,rss_mb,avail_mb,in_match,armed\n";
        WriteAll(kColumns, std::strlen(kColumns));
    }

    void WriteRow(uint64_t runSeconds)
    {
        // ---- the frame times: the last up to ten seconds, sorted -------------
        const uint64_t written = g_frameTimesWritten.load(std::memory_order_acquire);
        const uint32_t samples = uint32_t(std::min<uint64_t>(written, kRingFrames));
        for (uint32_t i = 0; i < samples; i++)
        {
            const uint64_t slot = (written - samples + i) % kRingFrames;
            g_samples[i] = g_frameTimes[slot].load(std::memory_order_relaxed);
        }
        std::sort(g_samples, g_samples + samples);

        const uint64_t p50 = Percentile(g_samples, samples, 50) / 1000;
        const uint64_t p95 = Percentile(g_samples, samples, 95) / 1000;
        const uint64_t p99 = Percentile(g_samples, samples, 99) / 1000;
        const uint64_t maximum = samples ? g_samples[samples - 1] / 1000 : 0;
        // A percentile over six frames is not a percentile; the column says
        // whether the window is long enough to read the others as one.
        const uint32_t armed = written >= kRingFrames ? 1 : 0;

        // ---- the last second --------------------------------------------------
        const uint32_t frames = g_windowFrames.exchange(0, std::memory_order_relaxed);
        const uint64_t draws = g_windowDraws.exchange(0, std::memory_order_relaxed);
        const uint32_t matchFrames = g_windowMatchFrames.exchange(0, std::memory_order_relaxed);
        const uint32_t jank20 = g_jank20.exchange(0, std::memory_order_relaxed);
        const uint32_t jank33 = g_jank33.exchange(0, std::memory_order_relaxed);
        const uint32_t jank50 = g_jank50.exchange(0, std::memory_order_relaxed);
        const uint32_t jank100 = g_jank100.exchange(0, std::memory_order_relaxed);

        const uint64_t presents = Since(g_previous.presents, vk::PresentedFrames());
        // The title's own waits for a D3D fence, which is where a CPU-bound frame
        // that is really waiting for the GPU shows up.
        const double gpuWaitMs = double(Since(g_previous.fenceWaitNanoseconds,
            gpu::detail::g_fenceWaits.nanoseconds.load(std::memory_order_relaxed))) / 1e6;

        // RUSAGE_SELF is every thread, so it is the sum the per-thread numbers
        // have to add up to; RUSAGE_THREAD is the render thread's own, which only
        // that thread can take, so it arrives as a snapshot (Frame).
        rusage usage{};
        uint64_t processCpuMicroseconds = 0;
        if (getrusage(RUSAGE_SELF, &usage) == 0)
            processCpuMicroseconds = uint64_t(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) * 1'000'000 +
                                     uint64_t(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec);
        const double cpuProcMs = double(Since(g_previous.processCpuMicroseconds, processCpuMicroseconds)) / 1000.0;
        const double cpuRenderMs = double(Since(g_previous.renderCpuMicroseconds,
            g_renderCpuMicroseconds.load(std::memory_order_relaxed))) / 1000.0;

        // ---- textures --------------------------------------------------------
        // The cache's counters are cumulative and the row wants this second's.
        // liveBytes/budgetBytes are where it stands now.
        const vk::texcache::Stats texture = vk::texcache::GetStats();
        const uint64_t evictPasses = Since(g_previous.evictPasses, texture.evictPasses);
        const uint64_t evictBytes = Since(g_previous.evictBytes, texture.evictBytes);
        const uint64_t evictMicroseconds = Since(g_previous.evictMicroseconds, texture.evictMicros);
        const uint64_t decodeMicroseconds = Since(g_previous.decodeMicroseconds, texture.decodeMicros);
        const uint64_t stageMicroseconds = Since(g_previous.stageMicroseconds, texture.stageMicros);
        const uint64_t readMicroseconds = Since(g_previous.readMicroseconds, texture.readMicros);
        const uint64_t newTextures = Since(g_previous.newTextures, texture.newTextures);
        const uint64_t restaged = Since(g_previous.restaged, texture.restaged);
        const uint64_t storeHits = Since(g_previous.storeHits, texture.storeHits);
        const uint64_t storeMisses = Since(g_previous.storeMisses, texture.storeMisses);

        // ---- the audio sink ---------------------------------------------------
        // Its depth window resets as it is read, which is one row of callbacks.
        const android::audio::Stats audio = android::audio::GetStats();
        const uint64_t audioMissing = Since(g_previous.missingFrames, audio.missingFrames);
        const uint64_t audioDropped = Since(g_previous.droppedFrames, audio.droppedFrames);

        // ---- the machine ------------------------------------------------------
        const uint64_t rssMb = ProcKilobytes("/proc/self/status", "VmRSS") / 1024;
        const uint64_t availableKb = ProcKilobytes("/proc/meminfo", "MemAvailable");
        const uint64_t availMb = (availableKb ? availableKb : ProcKilobytes("/proc/meminfo", "MemFree")) / 1024;

        const uint64_t pipelines = Since(g_previous.pipelines, g_pipelinesLinked.load(std::memory_order_relaxed));
        const uint64_t shaders = Since(g_previous.shaders, g_shadersBuilt.load(std::memory_order_relaxed));

        const uint64_t bootMs = BootMilliseconds();
        const uint64_t wallMs = WallMilliseconds();
        const int thermal = g_thermal.load(std::memory_order_relaxed);

        // cpu_ring/cpu_present/cpu_audio/cpu_decode: the threads those columns
        // name live outside this file (gpu.cpp, presenter.cpp, apu/audio.cpp,
        // texture_cache.cpp) and none of them hands its RUSAGE_THREAD time over
        // yet. A zero here is a missing counter, not a missing column.
        const double cpuRingMs = 0.0, cpuPresentMs = 0.0, cpuAudioMs = 0.0, cpuDecodeMs = 0.0;

        char line[1024];
        const int length = std::snprintf(line, sizeof line,
            "%llu,%llu,%llu,%u,"                        // boot_ms,wall_ms,run_s,frames
            "%llu,%llu,%llu,%llu,"                      // ft p50,p95,p99,max_us
            "%u,%u,%u,%u,"                              // jank20,33,50,100
            "%llu,%llu,"                                // draws,presents
            "%.1f,"                                     // gpu_wait_ms
            "%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,"            // cpu proc,render,ring,present,audio,decode_ms
            "%llu,%llu,"                                // tex_live_mb,tex_budget_mb
            "%llu,%.1f,%llu,"                           // evict_n,evict_mb,evict_us
            "%.1f,%.1f,%.1f,"                           // decode,stage,read_ms
            "%llu,%llu,%llu,%llu,"                      // new_n,restage_n,store_hit,store_miss
            "%llu,%llu,"                                // pipe_n,shader_n
            "%llu,%llu,%u,%u,"                          // au_missing,au_dropped,depth_min,depth_mean
            "%d,"                                       // thermal
            "%llu,%llu,"                                // rss_mb,avail_mb
            "%u,%u\n",                                  // in_match,armed
            (unsigned long long)bootMs, (unsigned long long)wallMs,
            (unsigned long long)runSeconds, frames,
            (unsigned long long)p50, (unsigned long long)p95,
            (unsigned long long)p99, (unsigned long long)maximum,
            jank20, jank33, jank50, jank100,
            (unsigned long long)draws, (unsigned long long)presents,
            gpuWaitMs,
            cpuProcMs, cpuRenderMs, cpuRingMs, cpuPresentMs, cpuAudioMs, cpuDecodeMs,
            (unsigned long long)(texture.liveBytes >> 20), (unsigned long long)(texture.budgetBytes >> 20),
            (unsigned long long)evictPasses, double(evictBytes) / 1048576.0, (unsigned long long)evictMicroseconds,
            double(decodeMicroseconds) / 1000.0, double(stageMicroseconds) / 1000.0,
            double(readMicroseconds) / 1000.0,
            (unsigned long long)newTextures, (unsigned long long)restaged,
            (unsigned long long)storeHits, (unsigned long long)storeMisses,
            (unsigned long long)pipelines, (unsigned long long)shaders,
            (unsigned long long)audioMissing, (unsigned long long)audioDropped,
            audio.depthMin, audio.depthMean,
            thermal,
            (unsigned long long)rssMb, (unsigned long long)availMb,
            matchFrames, armed);
        if (length > 0) WriteAll(line, size_t(std::min<int>(length, int(sizeof line) - 1)));
    }

    void Writer()
    {
        crash::RegisterThread("telemetry");
        platform::SetThreadName("telemetry");
        // Last in line: a row that is late by a few milliseconds is still a row,
        // and nothing here may take time from the frame or the mixer.
        platform::LowerThreadPriority();

        const uint64_t wallStart = WallMilliseconds();
        const uint64_t bootStart = BootMilliseconds();
        const auto start = Clock::now();
        auto next = start + std::chrono::seconds(1);

        std::string path;
        if (!OpenFile(path))
        {
            // One line, not a row: a run with no CSV is worth knowing about, and
            // the alternative is a file that is simply missing later.
            LOGW("telemetry: no file to write the CSV to; it is off");
            g_running.store(false, std::memory_order_relaxed);
            return;
        }
        LOGI("telemetry: %s", path.c_str());

        uint64_t ticks = 0;
        bool headerWritten = false;
        while (g_running.load(std::memory_order_relaxed))
        {
            std::this_thread::sleep_until(next);
            // The phone was asleep, or the run was paused: the seconds that went
            // by are not rows to write in a burst, they are gone.
            const auto woke = Clock::now();
            const auto period = std::chrono::seconds(1);
            next = woke > next + std::chrono::seconds(2) ? woke + period : next + period;
            ticks++;

            // The header names the renderer the rows are about, so it waits for
            // one to exist: the device, the budget the cache resolved to (which
            // comes up just after the device). A run that never brings a device
            // up gets it after a few seconds anyway.
            if (!headerWritten)
            {
                if ((!vk::pipeline::Ready() || !vk::textures::Budget()) && ticks < kHeaderWaitSeconds)
                    continue;
                WriteHeader(wallStart, bootStart);
                headerWritten = true;
            }
            WriteRow(ticks);
        }

        if (g_fd >= 0) close(g_fd);
        g_fd = -1;
        crash::UnregisterThread();
    }
}

void telemetry::Start()
{
    std::call_once(g_once, [] {
        // Unset means on; only an explicit "0" turns it off. env::Flag answers
        // false for both "unset" and "0", which is the wrong default here.
        const char* setting = env::Text("PES17_TELEMETRY");
        if (setting && setting[0] == '0' && setting[1] == 0) return;

        g_on.store(true, std::memory_order_relaxed);
        g_running.store(true, std::memory_order_relaxed);
        g_lastCpuSample = Clock::now();
        g_writer = std::thread(Writer);
        g_writer.detach();
    });
}

void telemetry::Frame(uint64_t nanoseconds, uint32_t draws, bool inMatch)
{
    if (!g_on.load(std::memory_order_relaxed))
    {
        // Nothing has started the writer yet: a desktop run, which has no
        // android/jni.cpp to do it, and where the CSV is just as useful. Start()
        // is idempotent and only spawns the writer, so this costs one load a
        // frame at most once.
        static bool tried = false;
        if (!tried)
        {
            tried = true;
            Start();
        }
        if (!g_on.load(std::memory_order_relaxed)) return;
    }

    g_frameTimes[g_frameTimesAt].store(nanoseconds, std::memory_order_relaxed);
    g_frameTimesAt = (g_frameTimesAt + 1) % kRingFrames;
    g_frameTimesWritten.store(g_frameTimesWritten.load(std::memory_order_relaxed) + 1,
                              std::memory_order_release);

    g_windowFrames.fetch_add(1, std::memory_order_relaxed);
    g_windowDraws.fetch_add(draws, std::memory_order_relaxed);
    if (inMatch) g_windowMatchFrames.fetch_add(1, std::memory_order_relaxed);

    // What the player felt: 20 ms misses a 60 Hz blank, 33 is two blanks, 50 is
    // visible in play, 100 is a stall that anybody watching sees.
    if (nanoseconds > 20'000'000) g_jank20.fetch_add(1, std::memory_order_relaxed);
    if (nanoseconds > 33'000'000) g_jank33.fetch_add(1, std::memory_order_relaxed);
    if (nanoseconds > 50'000'000) g_jank50.fetch_add(1, std::memory_order_relaxed);
    if (nanoseconds > 100'000'000) g_jank100.fetch_add(1, std::memory_order_relaxed);

    // RUSAGE_THREAD is the calling thread's own CPU time, so only this thread can
    // take it. Once a second keeps a system call off the per-frame path while
    // still giving the row a number that moves with play. A platform without it
    // leaves the column at 0 rather than reporting the process's time under the
    // render thread's name.
    const auto now = Clock::now();
    if (now - g_lastCpuSample >= std::chrono::seconds(1))
    {
#if defined(RUSAGE_THREAD)
        rusage usage{};
        if (getrusage(RUSAGE_THREAD, &usage) == 0)
            g_renderCpuMicroseconds.store(
                uint64_t(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) * 1'000'000 +
                uint64_t(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec),
                std::memory_order_relaxed);
#endif
        g_lastCpuSample = now;
    }
}

void telemetry::Compiles(uint64_t pipelines, uint64_t shaders)
{
    if (!g_on.load(std::memory_order_relaxed)) return;
    g_pipelinesLinked.store(pipelines, std::memory_order_relaxed);
    g_shadersBuilt.store(shaders, std::memory_order_relaxed);
}

void telemetry::SetThermal(int status)
{
    g_thermal.store(status, std::memory_order_relaxed);
}
