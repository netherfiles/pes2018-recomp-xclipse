#pragma once
// What the audio sink has to say about itself, for the telemetry row
// (runtime/telemetry.cpp) and the diagnostics report.
//
// The sink in audio.cpp keeps two counters and a window of buffer depths. A
// frame the callback needed and the ring could not supply is a click the player
// heard; a frame apu/audio.cpp refused to hand over because the sink was
// already deep is a frame the mixer produced and nobody played. They are
// different faults, so they are counted apart.
//
// Both are monotonic for the life of the process; the depth window starts
// again on every read, because it describes "since the last row", not the run.
#include <cstdint>

namespace android::audio
{
    struct Stats
    {
        // Frames the callback wanted and the ring was short of. At 48 kHz this
        // is 20.8 us of silence for each one.
        uint64_t missingFrames = 0;
        // Mixer frames apu::audio::SubmitFrame dropped above its high-water
        // mark (48 queued, 256 ms).
        uint64_t droppedFrames = 0;
        // Ring occupancy when each callback arrived, in 256-sample frames:
        // the smallest, and the mean, since the previous GetStats().
        uint32_t depthMin = 0;
        uint32_t depthMean = 0;
    };

    // An atomic snapshot; the two depth fields reset the window as they are
    // read, so a caller that polls once a second sees one second of callbacks.
    Stats GetStats();
}

#ifndef PES17_ANDROID
// The desktop build has no AAudio sink at all (runtime/android/*.cpp is only
// compiled on Android), but the telemetry writer is compiled everywhere: this
// keeps that one call site linking and reports no sink traffic, which is true.
namespace android::audio
{
    inline Stats GetStats() { return {}; }
}
#endif
