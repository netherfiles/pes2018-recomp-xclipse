#pragma once
// The heap trace's ring, shared by the hooks that fill it (pes_hooks.cpp) and
// the crash handler that prints it after a fault (crash.cpp).
//
// PES17_HEAP_TRACE=1 turns it on. An entry is a fixed set of five words and the
// ring is a fixed array, so a hook costs a few stores and never allocates,
// formats or locks -- it runs on every allocation in the title. Snapshot runs
// inside a signal handler, so it takes no lock and allocates nothing either:
// relaxed loads of a static array only.
#include <cstdint>

namespace heaptrace
{
    enum Kind : uint32_t
    {
        kNew = 1,      // 824AA460: operator new (a size, b alignment, c tag, d result, e guest lr)
        kAligned = 2,  // 824C5118: aligned allocator (a size, b alignment, c result, d guest lr, e tag)
        kMessage = 3,  // 83609818: message dispatch (a message id, b..e its four words)
    };

    struct Entry
    {
        uint32_t kind = 0;
        uint32_t a = 0;
        uint32_t b = 0;
        uint32_t c = 0;
        uint32_t d = 0;
        uint32_t e = 0;
    };

    // The newest `max` entries, newest first; returns how many were copied.
    // Zero when the trace is off or the build carries no diagnostics.
    unsigned Snapshot(Entry* out, unsigned max);
}
