// Hooks that make PES 2018 behave on a phone (as opposed to pes_debug.cpp's probes).
#include <ppc_recomp_shared.h>
#include "title.h"
#include "guest.h"
#include "heap_trace.h"
#include "log.h"
#include "crash.h"
#include "kernel/kernel.h"
#include "match_state.h"
#include <cstring>
#include <string>
#include "gpu/gpu.h"
#include <atomic>
#include <chrono>
#include <thread>

// D3D's fence poll (sub_82417C38; r3 -> { device, ?, last-seen retired, time }).
// It answers "still waiting" (1) until the word at [*(device+11024)] -- written by
// the command processor's end-of-pipe event -- reaches the fence, and the caller
// loops straight back into it. On the console that spin is free; here it ran the
// main thread at 100% of a big core, 40% of it in clock_gettime for the title's
// own timeout arithmetic, to wait out the 16 ms the GPU needs for a frame. When
// the answer is "still waiting", give the processor away first: the GPU thread
// that will move the word gets it, and the phone stays cool.
GUEST_HOOK(82417C38)
{
    GUEST_ORIG(82417C38)(ctx, base);
    if (ctx.r3.u32 == 1)
    {
        // A yield costs a syscall; a very short sleep lets the core idle. Short
        // enough that the frame is not delayed by it (the fence moves in ms).
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }
}

// Intro/ProcInstall, the step after the first-run language screens, is a four-state
// machine (object+88). It asks the install manager (a global, sub_83105358) whether a
// job is running: if there is one it goes to state 3, which sends the "proceed" event
// and the flow carries on to the data-pack steps and the main menu; if there is none it
// goes 0 -> 1 -> 2, and state 2 sets the main loop's exit flag -- the title ends itself.
// No install job is ever started here, so every first run quit to the launcher and the
// next one began at the first-run screens again (nothing is saved). Starting at state 3
// skips the quit and reaches TopMenu.
GUEST_HOOK(82FDDB20)
{
    const uint32_t self = ctx.r3.u32;
    if (*GuestPtr<be32>(self + 88) == 0) *GuestPtr<be32>(self + 88) = 3;
    GUEST_ORIG(82FDDB20)(ctx, base);
}

// The first-run flow reaches Intro/ProcInstall without ever running
// FirstFlow/ProcSystemDataSave, so no system data is created and the flow repeats on
// every launch. On the console the install step is where the title ends and is run
// again; here the procedure that makes the save is asked for in its place, once.
// (Its save is data.tmp, renamed to data.bin: NtSetInformationFile's rename.) The next
// launch then finds the package by enumerating (XamContentCreateEnumerator).
namespace
{
    std::string GuestString16(uint32_t address)
    {
        if (!address) return {};
        const uint32_t size = *GuestPtr<be32>(address + 16), capacity = *GuestPtr<be32>(address + 20);
        if (size > 256) return {};
        const uint32_t data = capacity >= 16 ? uint32_t(*GuestPtr<be32>(address)) : address;
        return std::string(reinterpret_cast<const char*>(guest::Base() + data), size);
    }
}

GUEST_HOOK(82F9CCE8)
{
    static std::atomic<int> once{ 0 }, logged{ 0 };
    const std::string name = GuestString16(ctx.r5.u32);
    if (logged++ < 400)
        LOGW("procedure: %s (from %08X on '%s')", name.c_str(), uint32_t(ctx.lr), crash::CurrentThreadName());
    // The play flag for telemetry and the stutter detector. Match/ is the
    // title's own match state; ExhibiMatchInit covers the load before the
    // first MatchSetup, and a new exhibition flow is back at the menu.
    if (name.compare(0, 6, "Match/") == 0 || name == "Exhibition/ExhibiMatchInit")
        match::Active().store(true, std::memory_order_relaxed);
    else if (name == "Exhibition/ExhibiFlowInit" || name == "Exhibition/ExhibiFlowEnd")
        match::Active().store(false, std::memory_order_relaxed);
    if (name == "Intro/ProcInstall" && once++ == 0)
    {
        const char text[] = "FirstFlow/ProcSystemDataSave";
        const uint32_t buffer = kernel::AllocateGuest(64, 16);
        std::memcpy(guest::Base() + buffer, text, sizeof text);
        *GuestPtr<be32>(ctx.r5.u32 + 0) = buffer;
        *GuestPtr<be32>(ctx.r5.u32 + 16) = uint32_t(sizeof text - 1);
        *GuestPtr<be32>(ctx.r5.u32 + 20) = 63;
    }
    GUEST_ORIG(82F9CCE8)(ctx, base);
}


// D3D's ring kicks (they store CP_RB_WPTR). See gpu::WaitAfterKick: while kits
// are being composed, the CPU waits for the command processor after each kick,
// so a resolve's write-back lands before the title reuses its memory.
GUEST_HOOK(824164B8)
{
    GUEST_ORIG(824164B8)(ctx, base);
    gpu::WaitAfterKick();
}
GUEST_HOOK(8242CCF8)
{
    GUEST_ORIG(8242CCF8)(ctx, base);
    gpu::WaitAfterKick();
}

// ---- PES17_HEAP_TRACE: what the allocator was asked for, and what it answered ----
//
// The crash this exists for is a SIGSEGV in the title's heap code seconds after
// a goal replay: a load at guest base + 0xFFFFFFFF from a register that held -1.
// Which allocation built that block, how big it was and what the allocator
// answered are what the report needs, and no host-side probe can be relied on
// for them, so the title's own functions are hooked -- by renaming their symbols
// (title.h) -- and each hook leaves five words in a static ring. They run on
// every allocation, so they must not allocate, format, take a lock or touch a
// register: a few relaxed stores and a counter, plus one log line the first time
// a hook runs and then once every kEvery calls, which is the only place a hook
// is allowed to cost anything. crash.cpp prints the ring with write() when the
// process faults.
//
// PES17_HEAP_TRACE=1 turns it on. Unset (or "0"), nothing is recorded and each
// hook calls the original and returns, exactly as before the hook existed.
#if PES17_DIAGNOSTICS
namespace
{
    constexpr unsigned kHeapSlots = 64;      // power of two: the ring's index is masked
    constexpr uint32_t kHeapEvery = 4096;    // power of two: the whole-call summary
    const bool gHeapTrace = env::Flag("PES17_HEAP_TRACE");

    struct HeapSlot
    {
        std::atomic<uint32_t> word[6];       // kind, then a..e
    };
    HeapSlot gHeapRing[kHeapSlots];
    std::atomic<uint32_t> gHeapNext{0};

    // crash.cpp reads these from a signal handler: a lock or a libatomic call
    // there would be the very hang the dump exists to avoid.
    static_assert(std::atomic<uint32_t>::is_always_lock_free);

    struct HeapCounters
    {
        std::atomic<uint32_t> calls{0}, zero{0}, minusOne{0};
    };
    HeapCounters gHeapNew, gHeapAligned, gHeapMessage;

    void HeapRecord(uint32_t kind, uint32_t a, uint32_t b, uint32_t c, uint32_t d, uint32_t e)
    {
        const uint32_t at = gHeapNext.fetch_add(1, std::memory_order_relaxed);
        HeapSlot& slot = gHeapRing[at & (kHeapSlots - 1)];
        const uint32_t words[5] = { a, b, c, d, e };
        for (int i = 0; i < 5; i++) slot.word[i + 1].store(words[i], std::memory_order_relaxed);
        slot.word[0].store(kind, std::memory_order_relaxed);   // last: a reader that sees the kind sees the words
    }

    // The answer of an allocation hook, counted: -1 and null are the two that
    // put a bad pointer in the title's hands, and they are what the summary
    // line reports beside the call count.
    uint32_t HeapCount(HeapCounters& counters, uint32_t result)
    {
        if (result == 0xFFFFFFFFu) counters.minusOne.fetch_add(1, std::memory_order_relaxed);
        else if (result == 0) counters.zero.fetch_add(1, std::memory_order_relaxed);
        return counters.calls.fetch_add(1, std::memory_order_relaxed) + 1;
    }

    // A guest word, only when the address is one the bank can hold
    // (guest_memory.h: 0x80000000-0xC0000000 is the whole guest space) and is
    // aligned. A hook that read a bad pointer would fault in place of the code
    // it is watching, which looks exactly like the crash being hunted.
    bool HeapReadable(uint32_t address)
    {
        return address >= 0x10000u && address < 0xC0000000u && (address & 3) == 0;
    }

    uint32_t HeapWord(uint8_t* base, uint32_t address)
    {
        return uint32_t(*reinterpret_cast<const be32*>(base + address));
    }

    // The five words 83609818 itself copies to its frame at r1+80: the message
    // id and its four words, behind self+4 -> +4 -> +32. (r5 is not that block
    // at entry -- it is a scratch register the function overwrites; the block is
    // built later from this chain, so this reads the same source it does.)
    bool HeapMessage(uint8_t* base, uint32_t self, uint32_t words[5])
    {
        if (!base || !HeapReadable(self + 4)) return false;
        const uint32_t first = HeapWord(base, self + 4);
        if (!HeapReadable(first + 4)) return false;
        const uint32_t second = HeapWord(base, first + 4);
        if (!HeapReadable(second + 32)) return false;
        const uint32_t block = HeapWord(base, second + 32);
        if (block >= 0xC0000000u - 16 || !HeapReadable(block)) return false;
        for (int i = 0; i < 5; i++) words[i] = HeapWord(base, block + uint32_t(i) * 4);
        return true;
    }
}

// The title's operator new: (size, alignment, tag) in r3/r4/r5, the block in
// r3 on return. The result is read from the context after the original ran, so
// the hook records the real answer instead of guessing it; nothing in the
// context is written, so the caller sees exactly what it would without the hook.
GUEST_HOOK(824AA460)
{
    if (!gHeapTrace)
    {
        GUEST_ORIG(824AA460)(ctx, base);
        return;
    }
    const uint32_t size = ctx.r3.u32, alignment = ctx.r4.u32, tag = ctx.r5.u32;
    const uint32_t from = uint32_t(ctx.lr);
    GUEST_ORIG(824AA460)(ctx, base);
    const uint32_t result = ctx.r3.u32;
    HeapRecord(heaptrace::kNew, size, alignment, tag, result, from);
    const uint32_t calls = HeapCount(gHeapNew, result);
    if (calls == 1)
        LOGI("heapdiag: hook 824AA460 alive (operator new) size=%X align=%X tag=%X -> %08X from %08X",
             size, alignment, tag, result, from);
    else if ((calls & (kHeapEvery - 1)) == 0)
        LOGI("heapdiag: 824AA460 calls=%u zero=%u minus1=%u last size=%X align=%X -> %08X",
             calls, gHeapNew.zero.load(std::memory_order_relaxed),
             gHeapNew.minusOne.load(std::memory_order_relaxed), size, alignment, result);
}

// The aligned allocator behind it: (size, alignment, tag), a header written
// below the block and the pointer returned in r3 (0 when the block could not be
// had). A -1 here is the value the crash notes name as a candidate.
GUEST_HOOK(824C5118)
{
    if (!gHeapTrace)
    {
        GUEST_ORIG(824C5118)(ctx, base);
        return;
    }
    const uint32_t size = ctx.r3.u32, alignment = ctx.r4.u32, tag = ctx.r5.u32;
    const uint32_t from = uint32_t(ctx.lr);
    GUEST_ORIG(824C5118)(ctx, base);
    const uint32_t result = ctx.r3.u32;
    HeapRecord(heaptrace::kAligned, size, alignment, result, from, tag);
    const uint32_t calls = HeapCount(gHeapAligned, result);
    if (calls == 1)
        LOGI("heapdiag: hook 824C5118 alive (aligned alloc) size=%X align=%X tag=%X -> %08X from %08X",
             size, alignment, tag, result, from);
    else if ((calls & (kHeapEvery - 1)) == 0)
        LOGI("heapdiag: 824C5118 calls=%u zero=%u minus1=%u last size=%X align=%X -> %08X",
             calls, gHeapAligned.zero.load(std::memory_order_relaxed),
             gHeapAligned.minusOne.load(std::memory_order_relaxed), size, alignment, result);
}

// The message dispatch/release the presentation path goes through: the object
// in r3, the message id and its four words behind the chain above. Recorded
// before the original runs, because it is the dispatch that provokes the
// allocation the crash is about.
GUEST_HOOK(83609818)
{
    if (!gHeapTrace)
    {
        GUEST_ORIG(83609818)(ctx, base);
        return;
    }
    const uint32_t self = ctx.r3.u32;
    const uint32_t from = uint32_t(ctx.lr);
    uint32_t words[5] = { self, 0, 0, 0, 0 };
    HeapMessage(base, self, words);   // on a broken chain the object stands in for the id
    HeapRecord(heaptrace::kMessage, words[0], words[1], words[2], words[3], words[4]);
    const uint32_t calls = gHeapMessage.calls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (calls == 1)
        LOGI("heapdiag: hook 83609818 alive (message dispatch) id=%08X %08X %08X %08X %08X from %08X",
             words[0], words[1], words[2], words[3], words[4], from);
    else if ((calls & (kHeapEvery - 1)) == 0)
        LOGI("heapdiag: 83609818 calls=%u last id=%08X %08X %08X %08X %08X",
             calls, words[0], words[1], words[2], words[3], words[4]);
    GUEST_ORIG(83609818)(ctx, base);
}

unsigned heaptrace::Snapshot(Entry* out, unsigned max)
{
    if (!out || !max) return 0;
    const uint32_t next = gHeapNext.load(std::memory_order_relaxed);
    unsigned count = 0;
    while (count < max && count < next && count < kHeapSlots)
    {
        const HeapSlot& slot = gHeapRing[(next - 1 - count) & (kHeapSlots - 1)];
        const uint32_t kind = slot.word[0].load(std::memory_order_relaxed);
        if (!kind) break;   // the slot has not been written yet
        Entry& entry = out[count++];
        entry.kind = kind;
        entry.a = slot.word[1].load(std::memory_order_relaxed);
        entry.b = slot.word[2].load(std::memory_order_relaxed);
        entry.c = slot.word[3].load(std::memory_order_relaxed);
        entry.d = slot.word[4].load(std::memory_order_relaxed);
        entry.e = slot.word[5].load(std::memory_order_relaxed);
    }
    return count;
}
#else
unsigned heaptrace::Snapshot(heaptrace::Entry*, unsigned) { return 0; }
#endif
