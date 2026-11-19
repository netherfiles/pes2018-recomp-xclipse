#pragma once
#include <cstdint>

// Always-on performance telemetry: one CSV row a second, written by a thread of
// its own, never by the render thread.
//
// Why a file and not the log: tele:/perf: repeat, and log.h's repeat table
// writes a repeated line once and then counts it -- twelve samples in
// forty-five minutes of a run. A hitch is gone from the log before anybody
// reads it. This asks the same questions of a file whose rows nothing collapses:
// frame time percentiles over the last ten seconds, the jank counts for the last
// second, and the texture, audio, draw and CPU counters that say whether a slow
// frame was an eviction wave, a resolve write-back burst, an audio sink that ran
// dry, or the CPU itself.
//
//   files/telemetry.csv    the rows; telemetry.csv.1 is the run before
//   PES17_TELEMETRY=0      off (the default is on)
//   PES17_TELEMETRY_PATH   a file to write instead of the app's files folder
//
// The render thread's part is one store into a ring and a handful of atomic
// adds per frame (Frame below); everything else -- the formatting, the /proc
// reads, the write -- is the writer thread's. Nothing here takes a lock on the
// render path, and nothing here is a diag::Stat, so it works in a release build
// (PES17_DIAGNOSTICS=OFF).
namespace telemetry
{
    // Opens the file and starts the writer thread. Idempotent, and does nothing
    // when PES17_TELEMETRY=0. Called once the app has set its options -- the CSV
    // header names the resolved ones (android/jni.cpp, in the game thread).
    // Frame() calls it as well, for a build that has no jni.cpp to do it.
    void Start();

    // One finished frame, from the render thread: its length in nanoseconds, the
    // draws recorded for it, and whether the title is in a match. The match flag
    // comes from the title's own procedure state, not the renderer's draw-based
    // world test: that test is false for a whole match on this title (54 draws a
    // frame, multisampling off). Feeds the percentile ring and the jank counters;
    // also samples the render thread's own CPU time, once a second.
    void Frame(uint64_t nanoseconds, uint32_t draws, bool inMatch);

    // The renderer's compile counters, from the thread that owns them (each swap,
    // renderer_resolve.cpp). Cumulative; the writer differences them.
    void Compiles(uint64_t pipelines, uint64_t shaders);

    // The phone's thermal status, PowerManager's THERMAL_STATUS_* (0 none .. 6
    // shutdown), as the app reports it. Any thread.
    void SetThermal(int status);
}
