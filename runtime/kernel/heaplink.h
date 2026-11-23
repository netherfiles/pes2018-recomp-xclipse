#pragma once
#include <cstdint>
#include "../guest_memory.h"

// The title's allocator keeps its free lists in guest memory: a block's two
// links are read out of the block and then dereferenced as pointers. A word
// there that is not a block address -- 0, 0xFFFFFFFF, a stale value from an
// overwritten block -- becomes a load from base + that word, outside the 4 GiB
// guest space, where nothing catches it: the replay-goal crash
// (base+0xFFFFFFFF, lwz at 0(r10) with r10 = -1).
//
// tools/fix_heaplinks.py puts the check below in front of every such
// dereference in the generated C++. A link that fails it is never followed: the
// unlink or the merge it belongs to is skipped, which is the path the allocator
// itself takes when a backlink does not answer, and the refusal is reported
// once (see pes17_heaplink_broken).
//
// A link is a block address, not a block: the size sits at block+0 and the list
// is threaded at block+8, so a node is at block+8 and 8-byte aligned. Checking
// 16 here would reject every node, because the 16-byte-aligned block base plus
// 8 is 8 mod 16.
inline bool pes17_heaplink_ok(uint32_t address)
{
    return address >= guest::kHeapBase && address < guest::kHeapEnd && (address & 7u) == 0;
}

// One refused link: `node` is the block whose links were read, `address` the
// word that cannot be one, `size` the block's size word (0 when unknown). Writes
// the first line and then counts, so a list that breaks on every allocation
// does not bury the first one. Called from the guest thread doing the walk --
// it takes the log's locks, so it must never be reached from a signal handler.
extern "C" void pes17_heaplink_broken(uint32_t node, uint32_t address, uint32_t size);

// How many links have been refused this session, for a report that wants the
// total after the log has moved on.
extern "C" uint32_t pes17_heaplink_count();
