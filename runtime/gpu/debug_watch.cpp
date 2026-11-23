#include "debug_watch.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>
#include <unistd.h>

#include <sys/mman.h>

#include "../guest_memory.h"
#include "../log.h"
#include "../kernel/kernel.h"
#include "../platform.h"
#include "gpu.h"

extern "C" volatile int pes_trap_inf_armed = 0;
extern "C" void pes_trap_inf_store(uint32_t address, uint32_t value)
{
    static std::atomic<int> told{ 0 };
    const int n = told++;
    if (n >= 700) { pes_trap_inf_armed = 0; return; }
    char why[96];
    std::snprintf(why, sizeof why, "trap: %cinf stored to guest %08X", (value >> 31) ? '-' : '+', address);
    platform::PrintBacktrace(why);
}

namespace
{
    std::atomic<bool> g_armed{ false };
    std::atomic<uint32_t> g_reports{ 0 };
    std::atomic<bool> g_open{ false };          // the pages are writable, waiting to be re-armed
    uint64_t g_first = 0, g_pages = 0;
    bool g_raw = false;          // g_first is a guest address, not a physical offset

    void Protect(bool writable)
    {
        if (g_raw)
        {
            mprotect(guest::Base() + g_first, g_pages * 4096, writable ? PROT_READ | PROT_WRITE : PROT_READ);
            return;
        }
        for (uint32_t window : { guest::kCachedBase, guest::kPhysicalBase, guest::kApertureBase })
            mprotect(guest::Base() + window + g_first, g_pages * 4096, writable ? PROT_READ | PROT_WRITE : PROT_READ);
    }

    // ---- write watch on a byte range (files/watch_heap) ----------------------
    // The page watch above reports every write in the pages it arms. A free-list
    // node is 8 bytes inside a page shared with dozens of other blocks, so this
    // mode arms the same kind of pages and filters the reports down to the
    // requested bytes; a write to the page outside the range is let through and
    // only counted. It has its own state, so watch_phys, watch_guest and
    // crowd_hunt behave exactly as before; a page watch already armed is turned
    // off when a range is armed, so only one of them reports at a time.
    constexpr uint32_t kRangeMaxPages = 8;      // 8 host pages: 32 KB at 4 KB, 128 KB at 16 KB
    constexpr uint32_t kRangeMaxReports = 200;  // then disarm itself, never fault forever
    constexpr uint32_t kRangeMaxFaults = 4000;  // in-range and neighbouring together
    constexpr uint32_t kRangeSnapshot = 8;      // the two link words

    std::atomic<bool> g_rangeActive{ false };
    std::atomic<bool> g_rangeOpen{ false };          // pages writable, waiting for the poller
    std::atomic<uint32_t> g_rangeReports{ 0 };
    std::atomic<uint32_t> g_rangeNeighbours{ 0 };    // faults in the page, outside the range
    std::atomic<uint32_t> g_rangeFaults{ 0 };        // both kinds
    std::atomic<bool> g_rangeNeighbourSeen{ false };
    std::atomic<uint32_t> g_rangePhys{ 0 };          // page-aligned physical offset
    std::atomic<uint32_t> g_rangePages{ 0 };
    std::atomic<uint32_t> g_rangeLo{ 0 };            // requested physical offset
    std::atomic<uint32_t> g_rangeLen{ 0 };
    std::atomic<uint32_t> g_rangePageBytes{ 4096 };
    std::atomic<uint64_t> g_rangeBefore{ 0 };        // snapshot at the previous fault
    std::atomic<bool> g_rangeHaveBefore{ false };

    uint32_t HostPageBytes()
    {
        const long page = sysconf(_SC_PAGESIZE);
        return page > 0 ? uint32_t(page) : 4096u;
    }

    // mprotect wants host pages: 4 KB on most devices, 16 KB on newer ones.
    void ProtectRange(bool writable)
    {
        const uint32_t first = g_rangePhys.load(std::memory_order_relaxed);
        const uint32_t pages = g_rangePages.load(std::memory_order_relaxed);
        const uint32_t page = g_rangePageBytes.load(std::memory_order_relaxed);
        if (!guest::Base() || !first || !pages) return;
        for (uint32_t window : { guest::kCachedBase, guest::kPhysicalBase, guest::kApertureBase })
            mprotect(guest::Base() + window + first, size_t(pages) * page,
                     writable ? PROT_READ | PROT_WRITE : PROT_READ);
    }

    void DisarmRange(const char* why)
    {
        if (!g_rangeActive.exchange(false)) return;
        g_rangeOpen = false;
        ProtectRange(true);
        LOGW("debugwatch: heap range off (%s): %u writes inside it, %u in the same page(s) outside it",
             why, g_rangeReports.load(std::memory_order_relaxed),
             g_rangeNeighbours.load(std::memory_order_relaxed));
    }

    void ArmRange(uint32_t address, uint64_t length)
    {
        // Re-arming while the old range's pages may still be read-only is a race
        // the fault handler cannot win (it would see the new range for an old
        // page and refuse a write it caused). One range at a time: disarm first.
        if (g_rangeActive.load(std::memory_order_relaxed))
        {
            LOGW("debugwatch: a heap range is already armed (%08X +%u); touch watch_heap_off before arming another",
                 g_rangeLo.load(std::memory_order_relaxed), g_rangeLen.load(std::memory_order_relaxed));
            return;
        }
        // The three windows alias one 512 MB bank; anything else would have its
        // physical offset collide with bank bytes that are not what was asked for.
        constexpr uint32_t kBankEnd = guest::kApertureBase + (guest::kPhysicalEnd - guest::kPhysicalBase);
        if (address < guest::kCachedBase || address >= kBankEnd)
        {
            LOGW("debugwatch: %08X is outside the 512 MB bank (80000000..%08X); watch_heap ignored",
                 address, kBankEnd);
            return;
        }
        const uint32_t page = HostPageBytes();
        const uint32_t phys = address & 0x1FFFFFFFu;
        const uint32_t offset = phys & (page - 1);
        uint64_t want = length ? length : kRangeSnapshot;
        if (want > 0x20000000u) want = 0x20000000u;
        const uint32_t first = phys & ~(page - 1);
        uint32_t pages = uint32_t((uint64_t(offset) + want + page - 1) / page);
        if (pages > kRangeMaxPages) pages = kRangeMaxPages;
        if (uint64_t(first) + uint64_t(pages) * page > 0x20000000ull)
            pages = uint32_t((0x20000000u - first) / page);
        if (!pages) return;
        const uint64_t armed = uint64_t(pages) * page - uint64_t(offset);
        const uint32_t len = uint32_t(std::min<uint64_t>(want, armed));
        if (!len) return;

        if (g_armed.exchange(false))
        {
            Protect(true);
            LOGW("debugwatch: a page watch was armed; it is turned off so the heap range is the one reporting");
        }
        g_rangePageBytes = page;
        g_rangePhys = first;
        g_rangePages = pages;
        g_rangeLo = phys;
        g_rangeLen = len;
        g_rangeReports = 0;
        g_rangeNeighbours = 0;
        g_rangeFaults = 0;
        g_rangeNeighbourSeen = false;
        g_rangeHaveBefore = false;
        g_rangeActive = true;
        ProtectRange(false);
        LOGW("debugwatch: heap range %08X +%llX (physical %08X, %u host page(s) of %u bytes, %u byte filter); writes in it are reported, other writes in those pages are counted only, at most %u reports",
             address, (unsigned long long)want, phys, pages, page, len, kRangeMaxReports);
    }

    // Called from the fault handler: no allocation, no locks, no std::string.
    bool RangeFault(const void* address)
    {
        const uint8_t* base = guest::Base();
        if (!base) return false;
        const uint32_t first = g_rangePhys.load(std::memory_order_relaxed);
        const uint32_t pages = g_rangePages.load(std::memory_order_relaxed);
        const uint32_t page = g_rangePageBytes.load(std::memory_order_relaxed);
        if (!first || !pages) return false;
        const uint8_t* p = static_cast<const uint8_t*>(address);
        bool ours = false;
        for (uint32_t window : { guest::kCachedBase, guest::kPhysicalBase, guest::kApertureBase })
        {
            const uint8_t* lo = base + window + first;
            if (p >= lo && p < lo + size_t(pages) * page) { ours = true; break; }
        }
        if (!ours) return false;

        // Let the write through; the poller makes the page read-only again.
        ProtectRange(true);
        g_rangeOpen = true;

        const uint32_t guest = uint32_t(p - base);
        const uint32_t phys = guest & 0x1FFFFFFFu;
        const uint32_t lo = g_rangeLo.load(std::memory_order_relaxed);
        const uint32_t len = g_rangeLen.load(std::memory_order_relaxed);
        const uint32_t faults = g_rangeFaults.fetch_add(1, std::memory_order_relaxed) + 1;
        if (phys < lo || phys - lo >= len)
        {
            // Page granularity: a neighbouring block in the same page. It has to
            // be allowed through all the same, or it faults again forever.
            g_rangeNeighbours.fetch_add(1, std::memory_order_relaxed);
            g_rangeNeighbourSeen = true;
            if (faults >= kRangeMaxFaults)
            {
                g_rangeActive = false;
                g_rangeOpen = false;
                ProtectRange(true);
            }
            return true;
        }

        // A write fault happens before the store: these bytes are the value
        // before this write, and the snapshot kept at the previous fault is
        // what the previous write left.
        uint64_t before = 0;
        const uint32_t n = len < kRangeSnapshot ? len : kRangeSnapshot;
        const uint8_t* bytes = base + guest::kCachedBase + lo;
        for (uint32_t i = 0; i < kRangeSnapshot; i++)
            before = (before << 8) | (i < n ? bytes[i] : 0);

        const uint64_t previous = g_rangeBefore.load(std::memory_order_relaxed);
        const bool havePrevious = g_rangeHaveBefore.load(std::memory_order_relaxed);
        const uint32_t report = g_rangeReports.fetch_add(1, std::memory_order_relaxed) + 1;
        const bool last = report >= kRangeMaxReports || faults >= kRangeMaxFaults;
        char why[288];
        std::snprintf(why, sizeof why,
                      "heap write #%u to guest %08X (physical %08X, %u byte(s)) thread %u; before=%016llX%s%016llX%s",
                      report, guest, phys, n, platform::ThreadId(), (unsigned long long)before,
                      havePrevious ? " previous-write-was=" : "",
                      havePrevious ? (unsigned long long)previous : 0ull,
                      last ? " (last report, disarming)" : "");
        platform::PrintBacktrace(why);

        g_rangeBefore = before;
        g_rangeHaveBefore = true;
        if (last)
        {
            g_rangeActive = false;
            g_rangeOpen = false;
            ProtectRange(true);
        }
        return true;
    }
}

void debugwatch::ArmFromFile(const std::string& path)
{
    // Polled, so it can be armed once the title is where the question is.
    std::thread([path] {
        for (;;)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            if (g_open.exchange(false) && g_armed.load(std::memory_order_relaxed) &&
                g_reports.load() < 900) Protect(false);
            if (g_rangeOpen.exchange(false) && g_rangeActive.load() &&
                g_rangeReports.load(std::memory_order_relaxed) < kRangeMaxReports)
                ProtectRange(false);
            {
                // The handler disarms itself after kRangeMaxReports; say so here,
                // where logging is safe.
                static bool rangeWas = false;
                const bool rangeNow = g_rangeActive.load(std::memory_order_relaxed);
                if (rangeWas && !rangeNow)
                {
                    ProtectRange(true);
                    LOGW("debugwatch: heap range finished: %u writes inside it, %u in the same page(s) outside it",
                         g_rangeReports.load(std::memory_order_relaxed),
                         g_rangeNeighbours.load(std::memory_order_relaxed));
                }
                rangeWas = rangeNow;
                // Page granularity is the cost of mprotect: say it once when it
                // first shows, so a flood of "wrong" addresses is not a mystery.
                static bool neighbourLogged = false;
                if (!neighbourLogged && g_rangeNeighbourSeen.load(std::memory_order_relaxed))
                {
                    neighbourLogged = true;
                    LOGW("debugwatch: page granularity: writes outside the %u-byte filter but inside an armed page were let through and counted, not reported",
                         g_rangeLen.load(std::memory_order_relaxed));
                }
            }
            static int tick = 0;
            // `touch files/crowd_hunt`: looks (every 20 ms) for a 256 KB run of the black DXT1 pattern
            // 00000000 FFFFFFFF -- the crowd's colour atlas, freshly made -- and arms the watch on it, so the
            // next writes to it are reported with their call stacks. The atlas moves from run to run, so it
            // cannot be named beforehand.
            {
                static bool hunting = false, found = false;
                if (tick % 300 == 150 && !found && guest::Base())
                    if (FILE* h = std::fopen((path.substr(0, path.rfind('/') + 1) + "crowd_hunt").c_str(), "r")) { std::fclose(h); hunting = true; }
                if (hunting && !found && guest::Base() && tick % 20 == 0)
                {
                    static const uint8_t pattern[8] = { 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF };
                    const uint8_t* physical = guest::Base() + guest::kPhysicalBase;
                    uint32_t run = 0;
                    for (uint32_t page = 0x10000; page < 0x14000; page++)   // 0x10000000 .. 0x14000000
                    {
                        const uint8_t* at = physical + (uint64_t(page) << 12);
                        const bool match = std::memcmp(at, pattern, 8) == 0 && std::memcmp(at + 8, pattern, 8) == 0 &&
                                           std::memcmp(at + 4088, pattern, 8) == 0;
                        run = match ? run + 1 : 0;
                        if (run == 64)
                        {
                            if (g_armed) Protect(true);
                            g_raw = false; g_first = uint64_t(page - 63) << 12; g_pages = 64; g_reports = 0; g_armed = true;
                            Protect(false);
                            found = true;
                            LOGW("crowd_hunt: the black DXT1 pattern, 64 pages at physical %08llX; watching it", (unsigned long long)g_first);
                            break;
                        }
                    }
                }
            }
            if (++tick % 300) continue;
            if (!guest::Base()) continue;   // a request left from the last run waits for the guest space
            {
                const std::string trap = path.substr(0, path.rfind('/') + 1) + "trap_inf";
                if (FILE* t = std::fopen(trap.c_str(), "r")) { std::fclose(t); std::remove(trap.c_str()); pes_trap_inf_armed = 1; LOGW("trap: armed"); }
            }
            {
                // `touch files/check_heap`: walk the process heap once.
                // `touch files/heap_watch` (kept): walk it on every poll and log the
                // first break, so the moment of corruption lines up with the log.
                const std::string dir = path.substr(0, path.rfind('/') + 1);
                const uint64_t frame = gpu::FrameCount();
                if (FILE* r = std::fopen((dir + "check_heap").c_str(), "r"))
                { std::fclose(r); std::remove((dir + "check_heap").c_str()); kernel::CheckProcessHeap(false, frame); }
                if (FILE* r = std::fopen((dir + "alloc_watch").c_str(), "r")) { std::fclose(r); kernel::ReportAllocRate(); }
                static bool brokenReported = false;
                if (FILE* r = std::fopen((dir + "heap_watch").c_str(), "r"))
                {
                    std::fclose(r);
                    if (!kernel::CheckProcessHeap(true, frame)) { if (brokenReported) {} brokenReported = true; }
                    else if (brokenReported) { brokenReported = false; LOGI("heapcheck: intact again"); }
                }
            }
            {
                // `echo "92921480 8" > files/watch_heap`: those guest bytes (hex
                // address, hex length) read-only, and every write that lands in them
                // reported with its call stack, thread and the value before it.
                // `touch files/watch_heap_off` disarms early; it also disarms itself
                // after kRangeMaxReports reports.
                const std::string dir = path.substr(0, path.rfind('/') + 1);
                if (FILE* off = std::fopen((dir + "watch_heap_off").c_str(), "r"))
                {
                    std::fclose(off); std::remove((dir + "watch_heap_off").c_str());
                    DisarmRange("watch_heap_off");
                }
                if (FILE* f = std::fopen((dir + "watch_heap").c_str(), "r"))
                {
                    unsigned long long a = 0, n = 8;
                    std::fscanf(f, "%llx %llx", &a, &n);
                    std::fclose(f); std::remove((dir + "watch_heap").c_str());
                    if (a) ArmRange(uint32_t(a & 0xFFFFFFFFull), n); else LOGW("debugwatch: watch_heap had no address");
                }
            }
            {
                // `touch files/report_memory`: the guest heaps' use, now.
                const std::string req = path.substr(0, path.rfind('/') + 1) + "report_memory";
                if (FILE* r = std::fopen(req.c_str(), "r")) { std::fclose(r); std::remove(req.c_str()); kernel::ReportMemory(); }
            }
            {
                // `echo "82235AE0 100" > files/dump_guest`: those guest bytes to files/guest_dump.bin.
                const std::string dir = path.substr(0, path.rfind('/') + 1), req = dir + "dump_guest";
                if (FILE* r = std::fopen(req.c_str(), "r"))
                {
                    unsigned long long a = 0, len = 0x1000;
                    std::fscanf(r, "%llx %llx", &a, &len);
                    std::fclose(r); std::remove(req.c_str());
                    a &= 0xFFFFFFFFull;                       // past 4 GB is unmapped: a bad request killed the game
                    len = std::min(len, 0x100000000ull - a);
                    if (FILE* o = std::fopen((dir + "guest_dump.bin").c_str(), "wb"))
                    {
                        std::fwrite(guest::Base() + (a & 0xFFFFFFFFull), 1, len, o);
                        std::fclose(o);
                        LOGW("dump_guest: %llX +%llX written", a, len);
                    }
                }
            }
            {
                // `echo "20026624 1" > files/watch_guest`: the same, for a guest virtual address.
                const std::string req = path.substr(0, path.rfind('/') + 1) + "watch_guest";
                if (FILE* f = std::fopen(req.c_str(), "r"))
                {
                    unsigned long long a = 0, n = 1;
                    std::fscanf(f, "%llx %llx", &a, &n);
                    std::fclose(f); std::remove(req.c_str());
                    if (g_armed) Protect(true);
                    g_raw = true; g_first = a & ~0xFFFull; g_pages = n ? n : 1; g_reports = 0; g_armed = true;
                    Protect(false);
                    LOGW("debugwatch: guest %08llX, %llu pages read-only", g_first, g_pages);
                }
            }
            FILE* file = std::fopen(path.c_str(), "r");
            if (!file) continue;
            unsigned long long address = 0, pages = 1;
            const int n = std::fscanf(file, "%llx %llx", &address, &pages);
            std::fclose(file);
            std::remove(path.c_str());
            if (n < 1 || !pages) continue;
            if (g_armed) Protect(true);
            g_raw = false;
            g_first = address & ~0xFFFull;
            g_pages = pages;
            g_reports = 0;
            g_armed = true;
            Protect(false);
            LOGW("debugwatch: physical %08llX, %llu pages read-only", g_first, g_pages);
        }
    }).detach();
}

bool debugwatch::HandleFault(const void* address)
{
    // The heap-range watch has its own pages and filter; it must be asked first
    // so it can tell a watched write from a neighbouring write in the same page.
    if (g_rangeActive.load(std::memory_order_relaxed) && RangeFault(address)) return true;
    if (!g_armed.load(std::memory_order_relaxed)) return false;
    const uint8_t* p = static_cast<const uint8_t*>(address);
    bool ours = false;
    if (g_raw)
    {
        const uint8_t* lo = guest::Base() + g_first;
        ours = p >= lo && p < lo + g_pages * 4096;
    }
    else
    for (uint32_t window : { guest::kCachedBase, guest::kPhysicalBase, guest::kApertureBase })
    {
        const uint8_t* lo = guest::Base() + window + g_first;
        if (p >= lo && p < lo + g_pages * 4096) ours = true;
    }
    if (!ours) return false;
    Protect(true);
    g_open = true;
    const uint32_t n = g_reports++;
    if (n < 900)
    {
        char why[96];
        std::snprintf(why, sizeof why, "debugwatch: write #%u to %p (physical %08llX)", n, address,
                      (unsigned long long)(p - guest::Base()) & 0x1FFFFFFFull);
        platform::PrintBacktrace(why);
    }
    return true;
}
