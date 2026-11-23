// Guards for the title's free-list links: the generated C++ refuses a link that
// is not a heap block (tools/fix_heaplinks.py inserts the check, heaplink.h
// explains why) and this reports the refusal.
#include "heaplink.h"
#include "../log.h"
#include <atomic>

namespace
{
    // Counted rather than written: a broken list trips this on every allocation,
    // and a line per trip is what buries the first line worth reading.
    std::atomic<uint32_t> g_refused{0};
}

extern "C" void pes17_heaplink_broken(uint32_t node, uint32_t address, uint32_t size)
{
    const uint32_t count = g_refused.fetch_add(1, std::memory_order_relaxed) + 1;

    // The first one, then at each doubling of the count, as the log itself counts
    // a repeated line (log.h, Repeat).
    if (count != 1 && (count & (count - 1)) != 0) return;

    // In the heap but not a node: the backlinks of the two neighbours disagreed
    // with the block. Outside it: the word is not an address at all (0, -1, a
    // value an object overwrote the links with).
    const bool inHeap = address >= guest::kHeapBase && address < guest::kHeapEnd;
    LOGW("heaplink: %s %08X on node %08X (block size %u) -- not dereferenced, %u refused so far",
         inHeap ? "backlink mismatch for" : "refused link", address, node, size, count);
}

extern "C" uint32_t pes17_heaplink_count()
{
    return g_refused.load(std::memory_order_relaxed);
}
