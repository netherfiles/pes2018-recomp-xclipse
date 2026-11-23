// Nt*VirtualMemory / Mm* -- guest memory management over the heaps in kernel.cpp.
#include <ppc_recomp_shared.h>
#include "kernel.h"
#include <cstdlib>
#include "../guest.h"
#include "../log.h"
#include "../crash.h"
#include "trace.h"
#include <map>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <atomic>
#include <tuple>
#include <vector>
#include <algorithm>

namespace
{
    // Live bytes per API, for `files/report_memory`.
    std::mutex g_accountLock;
    std::map<uint32_t, uint32_t> g_virtualLive, g_physicalLive;
    // Who asked, for the report: the guest return addresses two and three frames
    // up (the first is the engine's allocator wrapper, sub_832D2010 & co).
    struct Caller { uint32_t a = 0, b = 0, c = 0; };
    std::map<uint32_t, Caller> g_callers;
    Caller CallerOf(const PPCContext& ctx)
    {
        // Each frame's back chain is at 0(r1); a function saves its LR at -8
        // from its caller's r1 before the stwu.
        Caller out;
        uint32_t sp = ctx.r1.u32;
        uint32_t* slot[3] = { &out.a, &out.b, &out.c };
        for (int i = 0; i < 3; i++)
        {
            if (sp < 0x10000 || sp >= 0xE0000000u) break;
            sp = *GuestPtr<be32>(sp);
            if (sp < 0x10000 || sp >= 0xE0000000u) break;
            *slot[i] = *GuestPtr<be32>(sp - 8);
        }
        return out;
    }
    void Account(std::map<uint32_t, uint32_t>& live, uint32_t at, uint32_t size, const PPCContext* ctx = nullptr)
    {
        std::lock_guard held(g_accountLock);
        if (size) { live[at] = size; if (ctx) g_callers[at] = CallerOf(*ctx); }
        else { live.erase(at); g_callers.erase(at); }
    }
    // Takes `at` out of `live` if this API handed it out. The bank serves both
    // APIs and reuses a freed start first-fit, so a stale free names whatever
    // took that start since -- a heap segment, once: PES freed a 3600 KB physical
    // block (sub_8386CF60) after the bank had given its start to the process
    // heap, the next physical block landed on the heap's free lists, and the
    // first malloc to walk them looped forever on the Exhibition loading screen.
    // The console's Mm frees only what its own allocator holds; so does this.
    bool Release(std::map<uint32_t, uint32_t>& live, uint32_t at)
    {
        std::lock_guard held(g_accountLock);
        if (!live.erase(at)) return false;
        g_callers.erase(at);
        return true;
    }
    std::atomic<int> g_traceCommits{ 0 };
    constexpr uint32_t X_MEM_COMMIT  = 0x00001000;
    constexpr uint32_t X_MEM_RESERVE = 0x00002000;
    constexpr uint32_t X_MEM_RELEASE = 0x00008000;
}

PPC_FUNC(__imp__NtAllocateVirtualMemory)
{
    auto* basePtr = GuestPtr<be32>(ctx.r3.u32);
    auto* sizePtr = GuestPtr<be32>(ctx.r4.u32);
    uint32_t type = ctx.r5.u32;

    if (!basePtr || !sizePtr) { ctx.r3.u64 = X_STATUS_INVALID_PARAMETER; return; }

    uint32_t requested = *sizePtr;
    uint32_t wanted    = *basePtr;
    uint32_t size = (requested + 0xFFF) & ~0xFFFu;
    if (!size) { ctx.r3.u64 = X_STATUS_INVALID_PARAMETER; return; }

    if (size >= (1u << 20))
        LOGI("NtAllocateVirtualMemory: %u KB at %08X, type %08X, protect %08X (from %08X)",
             size >> 10, wanted, type, ctx.r6.u32, uint32_t(ctx.lr));
    // A commit onto an existing reservation just succeeds in place.
    if (wanted && (type & X_MEM_COMMIT) && !(type & X_MEM_RESERVE))
    {
        // Does the range lie inside a block the bank handed out? A commit that
        // does not "succeeds" on memory the bank still counts as free, and a
        // later physical allocation lands on it (Master League setup).
        uint32_t at = 0, extent = 0;
        const bool inside = kernel::FindAllocation(wanted, at, extent) &&
                            uint64_t(wanted) + size <= uint64_t(at) + extent;
        if (!inside)
        {
            static std::atomic<int> told{ 0 };
            if (told++ < 40)
                LOGW("NtAllocateVirtualMemory: COMMIT of %08X +%X is outside any live block (nearest %08X +%X) (from %08X)",
                     wanted, size, at, extent, uint32_t(ctx.lr));
        }
        else if (g_traceCommits.fetch_add(1) < 12)
            LOGI("NtAllocateVirtualMemory: commit %08X +%X inside block %08X +%X (from %08X)",
                 wanted, size, at, extent, uint32_t(ctx.lr));
        *sizePtr = size;
        ctx.r3.u64 = X_STATUS_SUCCESS;
        return;
    }
    if (wanted && (type & X_MEM_RESERVE))
    {
        // We hand out our own address and ignore the one asked for.
        static std::atomic<int> told{ 0 };
        if (told++ < 40)
            LOGW("NtAllocateVirtualMemory: RESERVE asked for %08X +%X, type %08X -- address ignored (from %08X)",
                 wanted, size, type, uint32_t(ctx.lr));
    }

    uint32_t addr = kernel::AllocateGuest(size, 0x1000);
    if (!addr) { LOGW("NtAllocateVirtualMemory: out of guest heap (%u bytes)", size); ctx.r3.u64 = X_STATUS_NO_MEMORY; return; }

    *basePtr = addr;
    *sizePtr = size;
    Account(g_virtualLive, addr, size, &ctx);
    ctx.r3.u64 = X_STATUS_SUCCESS;
}

PPC_FUNC(__imp__NtFreeVirtualMemory)
{
    // (BaseAddress*, RegionSize*, FreeType, DebugMemory). A decommit keeps the
    // reservation, which here is the allocation itself.
    auto* basePtr = GuestPtr<be32>(ctx.r3.u32);
    if (!basePtr) { ctx.r3.u64 = X_STATUS_INVALID_PARAMETER; return; }
    auto* sizePtr = GuestPtr<be32>(ctx.r4.u32);
    if (!(ctx.r5.u32 & X_MEM_RELEASE)) { ctx.r3.u64 = X_STATUS_SUCCESS; return; }
    const uint32_t at = *basePtr;
    uint32_t start = 0, size = 0;
    const bool known = kernel::FindAllocation(at, start, size) && start == at;
    if (known && !Release(g_virtualLive, at))
    {
        static std::atomic<int> told{ 0 };
        if (told++ < 20) LOGW("NtFreeVirtualMemory: %08X was not allocated by it -- left alone (from %08X)", at, uint32_t(ctx.lr));
        ctx.r3.u64 = X_STATUS_INVALID_PARAMETER;
        return;
    }
    if (known && kernel::FreeGuest(at))
    {
        if (sizePtr) *sizePtr = size;
        ctx.r3.u64 = X_STATUS_SUCCESS;
        return;
    }
    static std::atomic<int> told{ 0 };
    if (told++ < 10) LOGW("NtFreeVirtualMemory: %08X is not the start of a block (from %08X)", at, uint32_t(ctx.lr));
    ctx.r3.u64 = X_STATUS_INVALID_PARAMETER;
}

// X_MEMORY_BASIC_INFORMATION for the block holding the address. The CRT's
// munmap (sub_82595C00) frees a block only when BaseAddress and AllocationBase
// are the block, State is MEM_COMMIT and RegionSize fits; the old stub answered
// "the whole heap" for every address, so every large free failed silently and
// the match load leaked 340 MB of malloc's big chunks out of the bank.
PPC_FUNC(__imp__NtQueryVirtualMemory)
{
    auto* info = GuestPtr<be32>(ctx.r4.u32);
    if (!info) { ctx.r3.u64 = X_STATUS_INVALID_PARAMETER; return; }
    const uint32_t address = ctx.r3.u32, page = address & ~0xFFFu;
    uint32_t at = 0, size = 0;
    if (kernel::FindAllocation(address, at, size))
    {
        info[0] = page;                                   // BaseAddress
        info[1] = at;                                     // AllocationBase
        info[2] = 0x04;                                   // AllocationProtect = PAGE_READWRITE
        info[3] = at + size - page;                       // RegionSize, to the block's end
        info[4] = 0x1000;                                 // State = MEM_COMMIT
        info[5] = 0x04;                                   // Protect
        info[6] = 0x20000;                                // Type = MEM_PRIVATE
    }
    else
    {
        info[0] = page; info[1] = 0; info[2] = 0;
        info[3] = 0x1000;                                 // one free page
        info[4] = 0x10000;                                // State = MEM_FREE
        info[5] = 0x01;                                   // PAGE_NOACCESS
        info[6] = 0;
    }
    ctx.r3.u64 = X_STATUS_SUCCESS;
}

PPC_FUNC(__imp__MmAllocatePhysicalMemoryEx)
{
    uint32_t size      = ctx.r4.u32;
    uint32_t protect   = ctx.r5.u32;
    uint32_t minAddr   = ctx.r6.u32;
    uint32_t maxAddr   = ctx.r7.u32;
    uint32_t alignment = ctx.r8.u32 ? ctx.r8.u32 : 0x1000;
    // The page size is the caller's to choose, as Xenia has it: 64 KB with
    // MEM_LARGE_PAGES, 16 MB with MEM_16MB_PAGES, else 4 KB. MW2's runtime gave
    // every block 64 KB pages (its one 444 MB block needed them, docs/runtime.md),
    // but PES makes thousands of small vertex buffers -- a text mesh is 320 bytes
    // -- and at 64 KB each the 512 MB bank ran out at the team select: the meshes
    // were never made, and every team name drew through a null vertex buffer.
    constexpr uint32_t kLargePages = 0x20000000, k16MbPages = 0x80000000;
    const uint32_t page = (protect & k16MbPages) ? 0x1000000u : (protect & kLargePages) ? 0x10000u : 0x1000u;
    if (alignment < page) alignment = page;
    uint32_t addr = kernel::AllocatePhysical((size + page - 1) & ~(page - 1), alignment);
    if (size >= (1u << 20))
        LOGI("MmAllocatePhysicalMemoryEx: %u KB align %X range %08X..%08X -> %08X (from %08X)",
             size >> 10, alignment, minAddr, maxAddr, addr, uint32_t(ctx.lr));
    if (!addr)
        LOGW("MmAllocatePhysicalMemoryEx: out of physical heap (%u bytes, align %u, range %08X..%08X; %u MB already handed out)",
             size, alignment, minAddr, maxAddr,
             (kernel::PhysicalHighWater() - guest::kPhysicalBase) / (1024 * 1024));
    if (addr) Account(g_physicalLive, addr & 0x1FFFFFFFu, (size + page - 1) & ~(page - 1), &ctx);
    ctx.r3.u64 = addr;
}

PPC_FUNC(__imp__MmFreePhysicalMemory)
{
    // (Type, BaseAddress)
    ctx.r3.u64 = 0;
    if (!Release(g_physicalLive, ctx.r4.u32 & 0x1FFFFFFFu))
    {
        static std::atomic<int> told{ 0 };
        if (told++ < 20)
            LOGW("MmFreePhysicalMemory: %08X is not a live physical block -- left alone (from %08X on '%s')",
                 ctx.r4.u32, uint32_t(ctx.lr), crash::CurrentThreadName());
        return;
    }
    kernel::FreePhysical(ctx.r4.u32);
}

// MmGetPhysicalAddress is in video.cpp, next to the Vd* calls that consume it.
PPC_FUNC(__imp__MmQueryAddressProtect) { ctx.r3.u64 = 0x04; }
PPC_FUNC(__imp__MmSetAddressProtect)   { ctx.r3.u64 = 0; }

// X_MM_STATISTICS (104 bytes, the caller sets Length): page counts, as Xenia
// fills it. XAPI's GlobalMemoryStatus reads TotalPhysicalPages, the title's
// AvailablePages and its virtual-memory totals, and PES sizes from them -- the
// old stub wrote bytes where pages go and left those fields as stack garbage.
PPC_FUNC(__imp__MmQueryStatistics)
{
    auto* s = GuestPtr<be32>(ctx.r3.u32);
    if (!s) { ctx.r3.u64 = X_STATUS_INVALID_PARAMETER; return; }
    if (uint32_t(s[0]) != 104) { ctx.r3.u64 = 0xC0000004; return; }   // STATUS_INFO_LENGTH_MISMATCH
    for (int i = 1; i < 26; i++) s[i] = 0;
    s[1]  = 0x00020000;                                  // TotalPhysicalPages: 512 MB
    s[2]  = 0x00000300;                                  // KernelPages
    s[3]  = uint32_t(kernel::BankFreeBytes() / 4096);    // Title.AvailablePages
    s[4]  = 0x2FFE0000;                                  // Title.TotalVirtualMemoryBytes
    s[5]  = 0x00160000;                                  // Title.ReservedVirtualMemoryBytes
    s[6]  = 0x00001000;                                  // Title.PhysicalPages
    s[7]  = 0x00000010;                                  // Title.PoolPages
    for (int i = 8; i <= 13; i++) s[i] = 0x00000100;     // stack, image, heap, virtual, page table, cache
    s[25] = 0x0001FFFF;                                  // HighestPhysicalPage
    static std::atomic<int> told{ 0 };
    if (told++ < 3) LOGI("MmQueryStatistics: %u MB available to the title (from %08X)", uint32_t(s[3]) >> 8, uint32_t(ctx.lr));
    ctx.r3.u64 = X_STATUS_SUCCESS;
}

void kernel::ReportApiMemory()
{
    std::lock_guard held(g_accountLock);
    uint64_t v = 0, p = 0, vFar = 0;
    for (const auto& [at, size] : g_virtualLive) { v += size; if (at < guest::kCachedBase) vFar += size; }
    for (const auto& [at, size] : g_physicalLive) p += size;
    LOGI("memory: NtAllocateVirtualMemory %zu blocks %llu MB (%llu MB of it far), MmAllocatePhysicalMemoryEx %zu blocks %llu MB",
         g_virtualLive.size(), (unsigned long long)(v >> 20), (unsigned long long)(vFar >> 20),
         g_physicalLive.size(), (unsigned long long)(p >> 20));
    // The biggest holders by call chain (physical keys are offsets < 0x20000000,
    // virtual ones are addresses; they cannot collide with a live block of the other).
    std::map<std::tuple<uint32_t, uint32_t, uint32_t, bool>, std::pair<uint64_t, uint32_t>> by;
    for (const auto* live : { &g_virtualLive, &g_physicalLive })
        for (const auto& [at, size] : *live)
        {
            const Caller c = g_callers.count(at) ? g_callers[at] : Caller{};
            auto& e = by[{ c.a, c.b, c.c, live == &g_physicalLive }];
            e.first += size; e.second++;
        }
    std::vector<std::pair<uint64_t, decltype(by)::key_type>> top;
    for (const auto& [k, v] : by) top.push_back({ v.first, k });
    std::sort(top.rbegin(), top.rend());
    for (size_t i = 0; i < top.size() && i < 30; i++)
    {
        const auto& [a, b, c, phys] = top[i].second;
        LOGI("memory:   %6.1f MB in %5u %s blocks from %08X <- %08X <- %08X", top[i].first / 1048576.0,
             by[top[i].second].second, phys ? "physical" : "virtual", a, b, c);
    }
}

std::string kernel::OwnersOf(uint32_t guestAddress)
{
    const uint32_t phys = guestAddress & 0x1FFFFFFFu;
    std::string out;
    char line[160];
    std::lock_guard held(g_accountLock);
    // Virtual blocks are keyed by their cached-window address, physical ones by offset.
    for (const auto& [at, size] : g_virtualLive)
    {
        if (at < guest::kCachedBase || at >= guest::kPhysicalBase) continue;
        const uint32_t start = at & 0x1FFFFFFFu;
        if (phys < start || phys >= start + size) continue;
        const Caller c = g_callers.count(at) ? g_callers[at] : Caller{};
        std::snprintf(line, sizeof line, " [virtual %08X +%X from %08X<-%08X<-%08X]", at, size, c.a, c.b, c.c);
        out += line;
    }
    for (const auto& [at, size] : g_physicalLive)
    {
        if (phys < at || phys >= at + size) continue;
        const Caller c = g_callers.count(at) ? g_callers[at] : Caller{};
        std::snprintf(line, sizeof line, " [physical %08X +%X from %08X<-%08X<-%08X]", at, size, c.a, c.b, c.c);
        out += line;
    }
    return out.empty() ? " [no live block]" : out;
}

// ---------------------------------------------------------------------------
// The XAPI process heap's free lists and segments, read-only audit.
//
// PES's malloc is the 360 kernel's NT heap. The crash lands in its unlink
// (ppc_recomp.3.cpp:29963, guest 0x824394F4) on a "next" block whose Flink is 0
// and whose Blink is 0xFFFFFFFF, so the question is who wrote those two words.
// This walk reads the same metadata through the guest bank and nothing else:
// every dereference goes through HeapReadWord/HeapReadHalf, which refuse an
// address outside 0x80000000..0xA0000000 or one that is not aligned. The old
// word() checked nothing, so a corrupted link could fault the checker itself
// and read as a guest crash. No allocation, no writes, no guest calls here.
//
// Layout (32-bit NT heap as the 360 kernel carries it; the plan gave +40/+44,
// which are LastValidEntry and NumberOfUnCommittedPages in this structure):
//   heap + 0x00C  EncodeFlagMask        (0: HEAP_ENTRY.Size is stored plainly)
//   heap + 0x058/0x060  Segments[64]    (one pointer per segment; the gate picks)
//   heap + 0x180  FreeLists[128]        (LIST_ENTRY heads, 8 bytes each)
//   segment + 0x010  SegmentListEntry   (only walked to report coverage)
//   segment + 0x018  Heap back-pointer
//   segment + 0x01C  BaseAddress
//   segment + 0x020  NumberOfPages
//   segment + 0x024  FirstEntry
//   segment + 0x028  LastValidEntry              (+40)
//   segment + 0x02C  NumberOfUnCommittedPages    (+44)
//
// The segment table offset is the one the plan did not spell out. XP puts
// Segments[64] at 0x058; the free lists here sit 8 bytes above XP's, so the
// table is at 0x058 or 0x060. Both are tried and the first entry's own
// back-pointer and page count decide; if neither holds, the segment half is
// reported as "not covered" and never as corruption. The first walk also logs
// 0x000..0x5FF of the header once, so the offset can be read off one run
// (Revisi 8.1 C.3 asks for that dump before trusting the check).
namespace
{
    constexpr uint32_t kHeapBankLo = 0x80000000u;   // cached window, the bank
    constexpr uint32_t kHeapBankHi = 0xA0000000u;
    constexpr uint32_t kHeapFreeLists = 0x180;
    constexpr int      kHeapBins = 128;
    constexpr uint32_t kHeapSegmentCandidates[] = { 0x060, 0x058 };
    constexpr int      kHeapSegmentsMax = 64;
    constexpr uint32_t kHeapEncodeFlagMask = 0x00C;

    // Two identical breaks this far apart are corruption; closer ones may be
    // an allocator caught mid-update (the walk runs without the heap lock).
    constexpr uint64_t kHeapConfirmFrames = 60;
    constexpr int      kHeapFirstLogsPerClass = 5;
    constexpr int      kHeapCoverageEvery = 100;    // poller samples (~30 s at 300 ms)
    constexpr uint32_t kHeapMaxNodesBin = 20000;    // a walk budget, not a verdict
    constexpr uint32_t kHeapMaxNodesTotal = 200000;

    enum HeapFinding : uint32_t
    {
        kFindLink,      // node or Flink outside the bank, or not word aligned
        kFindBacklink,  // Blink is not the node we came from
        kFindForward,   // the next node's Blink does not name this node
        kFindSize,      // block size 0, or below the 16 bytes a free link needs
        kFindRange,     // the node is outside every validated heap segment
        kFindSegment,   // a segment pointer or field does not hold together
        kFindCount
    };

    const char* HeapFindingName(HeapFinding kind)
    {
        switch (kind)
        {
        case kFindLink:     return "link outside the bank";
        case kFindBacklink: return "back-link mismatch";
        case kFindForward:  return "forward link does not point back";
        case kFindSize:     return "block size zero or below 16 bytes";
        case kFindRange:    return "node outside every heap segment";
        case kFindSegment:  return "segment field invalid";
        default:            return "finding";
        }
    }

    bool HeapReadWord(uint32_t at, uint32_t& out)
    {
        if (at < kHeapBankLo || at > kHeapBankHi - 4 || (at & 3)) return false;
        out = uint32_t(*GuestPtr<be32>(at));
        return true;
    }

    bool HeapReadHalf(uint32_t at, uint16_t& out)
    {
        if (at < kHeapBankLo || at > kHeapBankHi - 2 || (at & 1)) return false;
        out = uint16_t(*GuestPtr<be16>(at));
        return true;
    }

    // What is remembered of a break: the same class at the same node with the
    // same predecessor, first seen at `frame`. A different node replaces it.
    struct HeapSighting { bool active = false; uint32_t bin = 0, node = 0, previous = 0; uint64_t frame = 0; };
    HeapSighting g_heapPending[kFindCount];
    bool g_heapConfirmed[kFindCount] = {};
    uint64_t g_heapHits[kFindCount] = {};       // findings, cumulative
    uint64_t g_heapFirstLogs[kFindCount] = {};
    uint64_t g_heapSamples = 0;
    bool g_heapHeaderLogged = false;
    bool g_heapSegmentGateLogged = false;
    bool g_heapBinGateLogged = false;
    bool g_heapChainLogged = false;

    void HeapDumpNode(uint32_t node)
    {
        char dump[192];
        int at = std::snprintf(dump, sizeof dump, "heapcheck:   words at node-8..node+24:");
        for (int i = -2; i < 6 && at > 0 && size_t(at) < sizeof dump; i++)
        {
            uint32_t v = 0;
            at += HeapReadWord(node + uint32_t(i) * 4, v)
                ? std::snprintf(dump + at, sizeof dump - size_t(at), " %08X", v)
                : std::snprintf(dump + at, sizeof dump - size_t(at), " ????????");
        }
        LOGW("%s", dump);
    }

    void HeapLogHeader(uint32_t heap)
    {
        // 0x000..0x5FF, sixteen words a line, once per process: the plan's
        // Revisi 8.1 C.3 asks for this dump before trusting any heap offset, and
        // it shows the free-list array (0x180) and the segment table at once.
        for (uint32_t row = 0; row < 0x600; row += 0x40)
        {
            char line[256];
            int at = std::snprintf(line, sizeof line, "heapcheck: header %03X:", row);
            for (uint32_t i = 0; i < 16 && at > 0 && size_t(at) < sizeof line; i++)
            {
                uint32_t v = 0;
                at += HeapReadWord(heap + row + i * 4, v)
                    ? std::snprintf(line + at, sizeof line - size_t(at), " %08X", v)
                    : std::snprintf(line + at, sizeof line - size_t(at), " ????????");
            }
            LOGI("%s", line);
        }
    }

    void HeapNoteFinding(HeapFinding kind, uint32_t bin, uint32_t node, uint32_t previous,
                         uint64_t frame, bool seen[], bool& confirmed)
    {
        seen[kind] = true;
        g_heapHits[kind]++;
        HeapSighting& pending = g_heapPending[kind];
        const bool same = pending.active && pending.bin == bin && pending.node == node &&
                          pending.previous == previous;
        if (!same)
        {
            if (!pending.active && g_heapFirstLogs[kind]++ < kHeapFirstLogsPerClass)
            {
                LOGW("heapcheck: %s at bin %u: node %08X, previous %08X (frame %llu); corrupt only if the same break holds %llu frames later",
                     HeapFindingName(kind), bin, node, previous, (unsigned long long)frame,
                     (unsigned long long)kHeapConfirmFrames);
                if (node >= kHeapBankLo && node <= kHeapBankHi - 4)
                    LOGW("heapcheck:   owners of node:%s", kernel::OwnersOf(node).c_str());
            }
            // A different break means the confirmed one is gone: an alternating
            // pair of nodes must not keep the class "corrupt" forever.
            if (g_heapConfirmed[kind]) LOGI("heapcheck: %s moved to another node", HeapFindingName(kind));
            g_heapConfirmed[kind] = false;
            pending = HeapSighting{ true, bin, node, previous, frame };
            return;
        }
        if (!g_heapConfirmed[kind] && frame >= pending.frame + kHeapConfirmFrames)
        {
            g_heapConfirmed[kind] = true;
            confirmed = true;
            LOGW("heapcheck: CORRUPT %s at bin %u: node %08X, previous %08X -- identical %llu frames apart",
                 HeapFindingName(kind), bin, node, previous, (unsigned long long)(frame - pending.frame));
            HeapDumpNode(node);
            LOGW("heapcheck:   owners of node:%s", kernel::OwnersOf(node).c_str());
        }
    }
}

bool kernel::CheckProcessHeap(bool quiet, uint64_t frame)
{
    const auto started = std::chrono::steady_clock::now();
    const uint64_t sample = ++g_heapSamples;
    // With the poller's frame stamp the two-sample rule is measured in frames;
    // a bare call (no stamp) counts calls instead.
    const uint64_t now = frame ? frame : sample;
    bool seen[kFindCount] = {};
    bool confirmedNow = false;

    uint32_t binCount = 0, binsUsed = 0, nodes = 0, abandoned = 0;
    uint32_t segments = 0, segmentLinks = 0, segmentOffset = 0;
    bool segmentTable = false;

    uint32_t heap = 0;
    if (!HeapReadWord(0x84065CE4, heap) || heap < kHeapBankLo || (heap & 3) ||
        heap > kHeapBankHi - (kHeapFreeLists + uint32_t(kHeapBins) * 8))
    {
        static bool told = false;
        if (!told) { told = true; LOGW("heapcheck: no usable process heap pointer yet (%08X)", heap); }
        return true;
    }

    uint32_t encode = 0;
    const bool encodeRead = HeapReadWord(heap + kHeapEncodeFlagMask, encode);
    const bool plainSizes = encodeRead && encode == 0;
    if (!g_heapHeaderLogged)
    {
        g_heapHeaderLogged = true;
        HeapLogHeader(heap);
        LOGI("heapcheck: heap %08X; free lists +%03X, EncodeFlagMask +%03X = %08X (%s)",
             heap, kHeapFreeLists, kHeapEncodeFlagMask, encode,
             plainSizes ? "entry sizes plain" : "entry sizes encoded or unreadable, size checks skipped");
    }

    // The segment table is used only when its first entry proves the offset:
    // a bank pointer whose +0x18 names this heap and whose page count is sane.
    for (uint32_t candidate : kHeapSegmentCandidates)
    {
        uint32_t first = 0, back = 0, pages = 0;
        if (HeapReadWord(heap + candidate, first) && first &&
            (first & 7) == 0 && first <= kHeapBankHi - 0x40 &&
            HeapReadWord(first + 0x18, back) && back == heap &&
            HeapReadWord(first + 0x20, pages) && pages >= 1 && pages <= 0x20000)
        {
            segmentTable = true;
            segmentOffset = candidate;
            break;
        }
    }
    if (!segmentTable && !g_heapSegmentGateLogged)
    {
        g_heapSegmentGateLogged = true;
        LOGW("heapcheck: no segment table at +058 or +060; segments not covered, see the header words");
    }

    // Segments first: each one's page range is what a free-list node has to
    // fall in, and the fields are checked on the way (Revisi 8.1 C.3: size,
    // alignment, segment range, Flink->Blink).
    struct HeapRange { uint32_t lo, hi; };
    HeapRange ranges[kHeapSegmentsMax];
    int rangeCount = 0;
    if (segmentTable)
    {
        for (int i = 0; i < kHeapSegmentsMax; i++)
        {
            uint32_t seg = 0;
            if (!HeapReadWord(heap + segmentOffset + uint32_t(i) * 4, seg)) break;
            if (!seg) continue;
            uint32_t back = 0, base = 0, pages = 0, first = 0, last = 0, uncommitted = 0;
            bool ok = seg >= kHeapBankLo && seg <= kHeapBankHi - 0x40 && (seg & 7) == 0 &&
                      HeapReadWord(seg + 0x18, back) && back == heap &&
                      HeapReadWord(seg + 0x1C, base) && base >= kHeapBankLo && base <= kHeapBankHi - 4 && (base & 3) == 0 &&
                      HeapReadWord(seg + 0x20, pages) && pages >= 1 && pages <= 0x20000 &&
                      HeapReadWord(seg + 0x24, first) && HeapReadWord(seg + 0x28, last) &&
                      HeapReadWord(seg + 0x2C, uncommitted);
            if (ok)
            {
                // LastValidEntry is BaseAddress + NumberOfPages*4096 (one past
                // the last block) in this heap, so `last` is the end, not before it.
                const uint64_t end = uint64_t(base) + uint64_t(pages) * 4096;
                ok = end <= kHeapBankHi && (first & 7) == 0 && (last & 7) == 0 &&
                     uint64_t(first) >= base && uint64_t(first) + 16 <= end &&
                     uint64_t(last) >= uint64_t(first) + 16 && uint64_t(last) <= end &&
                     uncommitted <= pages;
                if (ok && rangeCount < kHeapSegmentsMax)
                    ranges[rangeCount++] = HeapRange{ base, uint32_t(end) };
            }
            if (!ok)
            {
                HeapNoteFinding(kFindSegment, uint32_t(i), seg, 0, now, seen, confirmedNow);
                continue;
            }
            segments++;
        }
        // The list links are observed, not judged: the head's offset in the
        // header is not one this check knows, so a break here is reported once
        // as coverage and never as corruption.
        uint32_t first = 0;
        if (HeapReadWord(heap + segmentOffset, first) && first)
        {
            const uint32_t start = first + 0x10;
            uint32_t entry = start;
            for (uint32_t step = 0; step < uint32_t(kHeapSegmentsMax) + 2; step++)
            {
                uint32_t next = 0;
                if (!HeapReadWord(entry, next) || next < kHeapBankLo ||
                    next > kHeapBankHi - 4 || (next & 3))
                {
                    if (!g_heapChainLogged) { g_heapChainLogged = true; LOGI("heapcheck: segment links stop at %08X (next %08X); the table is still covered", entry, next); }
                    break;
                }
                if (next == start) { segmentLinks = step + 1; break; }
                uint32_t back = 0;
                if (!HeapReadWord(next + 4, back) || back != entry)
                {
                    if (!g_heapChainLogged) { g_heapChainLogged = true; LOGI("heapcheck: segment link %08X does not point back from %08X; the table is still covered", entry, next); }
                    break;
                }
                entry = next;
                segmentLinks = step + 1;
            }
        }
    }
    std::sort(ranges, ranges + rangeCount,
              [](const HeapRange& a, const HeapRange& b) { return a.lo < b.lo; });
    // The node and the two link words it carries must lie inside one segment.
    const auto inSegment = [&ranges, rangeCount](uint32_t at)
    {
        int lo = 0, hi = rangeCount - 1;
        while (lo <= hi)
        {
            const int mid = (lo + hi) / 2;
            if (at < ranges[mid].lo) hi = mid - 1;
            else if (at >= ranges[mid].hi || at + 8 > ranges[mid].hi) lo = mid + 1;
            else return true;
        }
        return false;
    };

    // A free-list array is only walked when it looks like one: at least 7 of 8
    // heads must satisfy the LIST_ENTRY invariant (head->Flink->Blink == head,
    // or the empty head pointing at itself). A wrong base or a heap still being
    // created then reads as "not covered" instead of as 128 corrupted bins.
    int saneBins = 0;
    for (int bin = 0; bin < kHeapBins; bin++)
    {
        const uint32_t head = heap + kHeapFreeLists + uint32_t(bin) * 8;
        uint32_t flink = 0, blink = 0, back = 0;
        if (!HeapReadWord(head, flink) || !HeapReadWord(head + 4, blink)) continue;
        if (flink < kHeapBankLo || flink > kHeapBankHi - 4 || (flink & 3) ||
            blink < kHeapBankLo || blink > kHeapBankHi - 4 || (blink & 3)) continue;
        if (flink == head && blink == head) { saneBins++; continue; }
        if (HeapReadWord(flink + 4, back) && back == head) saneBins++;
    }
    const bool binsTrusted = saneBins >= kHeapBins * 7 / 8;
    if (!binsTrusted && !g_heapBinGateLogged)
    {
        g_heapBinGateLogged = true;
        LOGW("heapcheck: free-list array at +%03X does not hold together (%d/%d heads); bins not covered, see the header words",
             kHeapFreeLists, saneBins, kHeapBins);
    }

    // Every bin, not only bin[0]: 128 LIST_ENTRYs at +0x180, 8 bytes each.
    for (int bin = 0; binsTrusted && bin < kHeapBins; bin++)
    {
        const uint32_t head = heap + kHeapFreeLists + uint32_t(bin) * 8;
        uint32_t previous = head, node = 0;
        binCount++;
        if (!HeapReadWord(head, node))
        {
            HeapNoteFinding(kFindLink, uint32_t(bin), head, previous, now, seen, confirmedNow);
            continue;
        }
        if (node != head) binsUsed++;
        uint32_t inBin = 0;
        while (node != head)
        {
            if (++inBin > kHeapMaxNodesBin || nodes >= kHeapMaxNodesTotal)
            {
                // A budget stop says nothing about corruption.
                abandoned++;
                break;
            }
            nodes++;
            uint32_t next = 0, blink = 0;
            if (!HeapReadWord(node, next) || !HeapReadWord(node + 4, blink))
            {
                HeapNoteFinding(kFindLink, uint32_t(bin), node, previous, now, seen, confirmedNow);
                break;
            }
            if (blink != previous)
            {
                HeapNoteFinding(kFindBacklink, uint32_t(bin), node, previous, now, seen, confirmedNow);
                break;
            }
            // Flink->Blink == node; the last node's Flink is the head, whose
            // Blink must name the node instead.
            uint32_t back = 0;
            const uint32_t check = next == head ? head : next;
            if (check < kHeapBankLo || check > kHeapBankHi - 4 || (check & 3) ||
                !HeapReadWord(check + 4, back) || back != node)
            {
                HeapNoteFinding(kFindForward, uint32_t(bin), node, previous, now, seen, confirmedNow);
                break;
            }
            if (plainSizes)
            {
                // A free block carries its two link words in its first 16 bytes.
                uint16_t size = 0;
                if (!HeapReadHalf(node - 8, size) || size < 2)
                {
                    HeapNoteFinding(kFindSize, uint32_t(bin), node, previous, now, seen, confirmedNow);
                    break;
                }
            }
            if (rangeCount && !inSegment(node))
            {
                HeapNoteFinding(kFindRange, uint32_t(bin), node, previous, now, seen, confirmedNow);
                break;
            }
            previous = node;
            node = next;
        }
    }

    for (int k = 0; k < kFindCount; k++)
    {
        if (seen[k]) continue;
        if (g_heapConfirmed[k]) LOGI("heapcheck: %s cleared", HeapFindingName(static_cast<HeapFinding>(k)));
        g_heapConfirmed[k] = false;
        g_heapPending[k].active = false;
    }

    bool corrupt = false;
    for (int k = 0; k < kFindCount; k++) corrupt = corrupt || g_heapConfirmed[k];
    const double elapsed = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - started).count();

    // Coverage and cost, so the price of the check can be judged: on demand
    // always, while watch polling once at the start and then every 100 samples.
    static bool lastCorrupt = false;
    if (!quiet || sample == 1 || sample % kHeapCoverageEvery == 0 || corrupt != lastCorrupt)
    {
        LOGI("heapcheck: walk #%llu coverage: bins %u/%u (%u used, %d sane heads), segments %u (table %s at +%03X, %u links), nodes %u, abandoned %u, %.3f ms; findings: link %llu back-link %llu forward %llu size %llu range %llu segment %llu",
             (unsigned long long)sample, binCount, uint32_t(kHeapBins), binsUsed, saneBins, segments, segmentTable ? "yes" : "no",
             segmentOffset, segmentLinks, nodes, abandoned, elapsed,
             (unsigned long long)g_heapHits[kFindLink], (unsigned long long)g_heapHits[kFindBacklink],
             (unsigned long long)g_heapHits[kFindForward], (unsigned long long)g_heapHits[kFindSize],
             (unsigned long long)g_heapHits[kFindRange], (unsigned long long)g_heapHits[kFindSegment]);
        lastCorrupt = corrupt;
    }
    return !corrupt;
}
