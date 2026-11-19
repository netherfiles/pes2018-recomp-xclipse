#pragma once
#include <cstdint>

// What the texture cache and the store behind it have counted so far, for the
// telemetry line: one snapshot, taken without stopping the uploads. The counters
// it reads only move forward, and the ones another thread writes -- the uploads
// are parsed on the command stream's thread, the store has a thread of its own,
// the eviction pass runs on the frame's -- are atomic, so a reader on the
// renderer's thread sees either side of a step and never a torn value.
namespace vk::texcache
{
    struct Stats
    {
        uint64_t liveBytes = 0, budgetBytes = 0;
        // One pass is one frame out of kSweepFrames: how often it went, how much
        // it let go, and how long it held the frame's thread.
        uint64_t evictPasses = 0, evictTextures = 0, evictBytes = 0, evictMicros = 0;
        // Spent turning guest textures into images: the BC decode (or the store's
        // read), and the copy into the staging buffer.
        uint64_t decodeMicros = 0, stageMicros = 0, readMicros = 0;
        uint64_t newTextures = 0, restaged = 0;
        // The store's answers for a BC texture already converted in an earlier
        // session (texture_store.h).
        uint64_t storeHits = 0, storeMisses = 0;
    };
    Stats GetStats();   // atomics snapshot, thread-safe
}
