#pragma once
#include <atomic>

// The title's own play state, set from the procedure name it passes to the
// hook at 82F9CCE8 (Match/..., Exhibition/ExhibiMatchInit). Telemetry and the
// stutter detector cannot use the renderer's draw-based "world" test: this
// title draws about 54 times a frame with multisampling off, below the
// 200-draw / 8-multisampled threshold, so that test is false for a whole match.
namespace match
{
    inline std::atomic<bool>& Active()
    {
        static std::atomic<bool> active{ false };
        return active;
    }
}
