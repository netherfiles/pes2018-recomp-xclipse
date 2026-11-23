# Switches

The runtime is configured through `PES17_*` environment variables. A flag is on
when set to anything but empty or `0` (`runtime/env.h`).

## The settings file

A file named `.env` beside the executables (in the current directory for a
development run) holds switches as `NAME=value`, one a line. The runtime reads
it first thing (`runtime/settings.cpp`) and sets each `PES17_` switch the
environment does not already have, so a variable given on the command line
decides. The launcher writes its own settings there (RESOLUTION is
`PES17_SCALE`, FPS LIMIT is `PES17_FPS_LIMIT`) and keeps the other lines. When the
file has no `PES17_FPS_LIMIT`, the launcher writes the screen's refresh rate as
it opens (60 for a 60 Hz screen, which is the console's pacing). `PES17_LAUNCHER_SOUNDS=0`, written there
by hand, keeps the launcher's own menus silent.

Switches marked **R** are read by every build. The rest are diagnostic: a build
without diagnostics (`RELEASE=1 ./build.sh`, `-DPES17_DIAGNOSTICS=OFF`) treats
them as unset whatever the environment says, and also drops the keys `F7`, `F10` and `F11`, `kill -USR2` and the end-of-run reports.

## Running

| switch | | effect |
|---|---|---|
| `PES17_WINDOW=0\|1` | R | the window, which every build opens; `PES17_WINDOW=0` keeps it shut, for a headless run. Closing it ends the run as `PES17_RUN_SECONDS` does, with the reports. The frame keeps its proportions in any window shape |
| `PES17_FULLSCREEN=1` | R | starts fullscreen, borderless at the desktop's resolution. `F8` toggles it |
| `PES17_LOG_FILE=<path>` | R | writes the log there, creating its folder. The log goes to stderr, so `> file` does not catch it. |
| `PES17_REPORT=1` | R | what a bug report needs, as the launcher's REPORT A BUG sets it: the log names the graphics driver and ends with the run's frame rate, frame time spread and what was compiled at a draw |
| `PES17_LOG_TIME=1` | | no longer read: every line is stamped with the time, whether it is set or not, so setting it changes nothing |
| `PES17_LOG_ALL=1` | | writes every line as it comes. Without it a line that repeats is written once and then counted -- the 1st, 2nd, 4th, 8th ... with the size of the repetition beside it, which is never above the truth and within a factor of two of it -- and two lines that differ only in an address, a size or a shader hash are one kind of line. A run that logs one thing a thousand times buries the line that says what it was |
| `PES17_NO_AUTO_SAVE_DEVICE=1` | R | leaves the save-device choice to the title's prompt instead of choosing the hard disk |
| `PES17_NO_AUDIO=1` | R | opens no playback device; the mixer and decoder still run ([audio.md](audio.md)) |
| `PES17_RUN_SECONDS=<n>` | | reports and exits after n seconds |
| `PES17_WATCHDOG=<seconds>` | | when it fires: the `PES17_DUMP` blocks, what every guest thread is blocked on (handle and caller) or spinning on, and every thread's backtrace, which is the guest call stack; then exits |
| `kill -USR2 <pid>` | | every thread's stack, as the watchdog prints them, and the run continues (Linux). Sent a few times, it tells a stuck thread from a looping one |
| `PES17_INPUT_SCRIPT="20:start,26:a"` | | presses pad inputs at wall-clock seconds. An entry names a button (`a b x y lb rb start back up down left right lthumb rthumb`), a trigger (`lt`, `rt`) or a stick direction (`lx+ lx- ly+ ly- rx+ rx- ry+ ry-`), with an optional hold time: `"40:lt:25"` holds the left trigger 25 s. Default hold 0.2 s. A name ending in `@2`, `@3` or `@4` is that player's controller, which the script then stands in for: `"5:a@2"`. `guide` is the Guide button, which opens the sign-in screen |

## The log

Every line the runtime writes -- to stderr on a desktop, to logcat and the
`game.log` file on Android -- starts with when it was written:

```
2026-11-12T07:03:22.123Z 123456.789 [i] android: external /sdcard/Android/data/...
```

The first field is UTC with milliseconds, never local time, so two files written
in different timezones can still be compared line by line. The second is the
device's uptime with three decimals, `CLOCK_BOOTTIME`, which is the number
Android's `SystemClock.elapsedRealtime()` reports, so a line here can be put
beside the same moment in logcat or in a screen recording. Then comes the level
-- `[i]`, `[w]`, `[E]`, `[k]` -- and the message after it is unchanged, which is
what `launcher/report.cpp` and the tools match on. There is no thread id.

Each file begins with one line saying what wrote it, the same line in the
internal and the external copy:

```
===== new run 2026-11-12T07:03:22.123Z boot=123456.789 pid=1234 proc=game abi=arm64-v8a device=2407FPN8EG api=36 =====
```

`proc` is `game` for the run and `launcher` for the launcher, `abi` is the
build's, `device` is `ro.product.model` and `api` what the phone runs. The line
still starts with `===== new run`, which is what the tools grep for to find
where a run starts.

A file already past 4 MB when it is opened is renamed to `<name>.1` -- the older
`.1` removed first -- and a new one is started, for the internal and the external
copy both. So `game.log` is the run that is happening and `game.log.1` the one
before it, which is normally the one a bug report is about; nothing is deleted,
and nothing is turned over in the middle of a run. If the rename fails (FUSE can
refuse one) the old file is removed instead and a single warning line says so.

A line that repeats is written once and then counted, with the count beside it
at the 2nd, 4th, 8th ... repetition, and the counts still owed are written out
when the log is next opened. The table holds 512 kinds of line; a kind that does
not fit is not written at all, and one `[w] log: repeat table full, N kinds
suppressed` line says so. `PES17_LOG_ALL=1` writes every line as it comes.

A run that is not tracing writes a few hundred lines. The traces,
`PES17_LOG_ALL=1` and a title that logs one thing a thousand times write far
more: that is the runs the counting above exists for, and the ones where 4 MB is
reached before the run ends.

## The guest

| switch | effect |
|---|---|
| `PES17_TRACE_INDIRECT` | CMake option, on by default: reports an indirect call with no recompiled target instead of jumping to null |
| `PES17_TRACE_FILES=1` | every file opened, read and written. The files asked for and not found are listed at exit regardless |
| `PES17_TRACE_XMA=1` | every XMA decoder kick with its buffers, and FFmpeg's log |
| `PES17_DUMP=<hex>[:words][,...]` | blocks of guest memory to print when the watchdog fires (16 words by default) |
| `PES17_WATCH=<hex>[:bytes]` | a write watchpoint (Linux): the page is made read-only, and every guest write into the range is reported with its call stack and thread, up to `PES17_WATCH_REPORTS` (20); the command processor's writes there are reported too. It logs when it gives up on a busy page |

## The command stream

| switch | effect |
|---|---|
| `PES17_DUMP_SHADERS=<dir>` | every distinct shader's microcode, for `tools/xenos_shader.py` and `translate-shader` |
| `PES17_DUMP_TEXTURES=<dir>` | every distinct bound texture, untiled, as PNM with each mip level, its fetch constant logged. `PES17_DUMP_TEXTURES_RAW=1` also writes the guest bytes; `PES17_DUMP_TEXTURES_AFTER=<seconds>` starts later, since a streamed texture is empty at its first bind |
| end-of-run reports | packets by opcode, frame times, draws and presents per second, arena copies, draws per render target, texture and shader work |

## Drawing

| switch | | effect |
|---|---|---|
| `PES17_SCALE=<2 or 3>` | R | draws every surface that many times wider and taller than the title's: a 2560x1440 or 3840x2160 frame. The launcher's RESOLUTION entry sets it in the settings file ([rendering.md](rendering.md#resolution-scale)) |
| `PES17_ASPECT=<w/h>` | R | a screen wider than 16:9 (up to 3): the title's cameras are given that aspect (the 16:9 floats at `T_DATA_Aspect16x9`/`b`, `title.h`) and the 1280x720 frame is shown stretched to it, so the view is wider and the pitch keeps its shape; the interface is stretched. The Android app sets it from the screen under Settings > Widescreen |
| `PES17_FPS_LIMIT=<n>` | R | how many frames a second the title draws at most. Unset or 60 is the console's pacing, a frame at every blank. Any other number takes the title off the blank, with that number as its own limiter's; 0 is no limit. The limiter counts whole milliseconds a frame, so 120 gives 125 and 144 gives 166. The launcher's FPS LIMIT entry sets it in the settings file ([rendering.md](rendering.md#more-than-60-frames-a-second)) |
| `PES17_MSAA=<n>` | R | draws every surface the title multisamples at n samples, rounded down to what the device offers |
| `PES17_NO_MSAA=1` | R | draws the title's 2x and 4x surfaces at one sample |
| `PES17_FRAMEGEN=1` | R | frame generation ([frame-generation.md](frame-generation.md)): with the title at half the display's rate (`PES17_FPS_LIMIT=30` on 60 Hz) the window shows a frame made from each two real ones between them. `files/framegen_off` turns it off live. Settings > Graphics > Frame generation sets it and the 30 limit |
| `PES17_MAX_ANISO=<n>` | R | caps the title's anisotropic filtering at n:1 (1 is none; 16 by default, 4 on Android) |
| `PES17_YIELD_SLEEP_US=<n>` | R | the most a guest spin wait sleeps per yield (`runtime/kernel/yield_backoff.h`); 0 is plain yields. Unset: 100 under an FPS limit below 60 or while Android reports thermal status MODERATE or worse, plain yields otherwise |
| `PES17_ARENA_MB=<n>` | R | the upload arena for constants, vertices and indices, shared by the frame slots (512) |
| `PES17_TEXTURE_BUDGET_MB=<n>` | R | the texture cache's budget, past which it lets go of what has not been bound lately, even textures whose memory is unchanged (defaults to half the device-local memory within 256 MB to 2 GB on desktop, and 128 MB on Android) |
| `PES17_PIPELINE_CACHE=<file>` | R | keeps the driver's compiled pipelines across runs |
| `PES17_SHADER_CACHE=<file>` | R | records every pipeline a run needed and builds them at the next start-up |
| `PES17_NO_PIPELINE_LIBRARIES=1` | R | builds each pipeline whole at its first draw instead of linking it from shaders compiled at load ([rendering.md](rendering.md)) |
| `PES17_SYNC_LARGE_PIPELINES=1` | R | an experiment, off by default: on a frame that was already slow (25 ms or more, 40 fps), builds a blocked large draw's pipeline on the render thread instead of skipping the draw. Paced from the end of the last successful build by eight times its duration (20 ms at least), one build a frame and at most 40 ms of builds a frame; the session has 5000 ms and 100 attempts, and a single build over 150 ms gives the rest of the session back to the background worker. A build that loses the race to another thread costs nothing, a driver out-of-memory failure is retried once 30 frames later, a rejection is not tried again. The spend and denials are reset when the renderer shuts down. Separately, and by default with no switch and no 25 ms frame gate, the first draw into an off-screen target 640 px wide or more also builds its pipeline synchronously, bounded by the session budget, the denylist and the retry wait: the kit composition it writes happens once, and a skipped one is never drawn again. Settings > Advanced > Experiments sets it |
| `PES17_TRACE_SHADER_LOADS=1` | | each shader compiled at load and each the command stream loads, with its hash |
| `PES17_TRACE_DRAWS=<n>` | | describes n draw packets in full; `PES17_TRACE_DRAWS_AFTER=<presents>` starts later |
| `PES17_TRACE_FRAME=<n>` | | narrates n frames' render targets and resolves |
| `PES17_TRACE_RENDER_AFTER=<frames>` | | where the traces below start |
| `PES17_TRACE_PASSES=<n>` | | a frame's passes in order: target, colour mask, depth state, scissor |
| `PES17_TRACE_SHADER_VERTS=<hex>[,...]` | | for draws with those shaders: the first float constants, the fetch constants and the vertices they name |
| `PES17_RENDER=0` | | executes and counts the stream but draws nothing |
| `PES17_ONLY_SHADER=<hex>[,...]`, `PES17_SKIP_SHADER=<hex>[,...]` | | draws only, or all but, the draws using those shaders |
| `PES17_NO_CULL=1`, `PES17_NO_DEPTH_TEST=1` | | take culling or the depth test out of every pipeline |
| `PES17_NO_SHADOW=1` | | no shadow of guest memory: every draw copies its vertex data into the arena. It is 512 MB behind the arena, so Android sets it itself on a device of six gigabytes or less |
| `PES17_ADRENO_BC=hw\|dxt1` | | how much of the Snapdragon workaround applies when the driver's own BC sampling is on: `hw` keeps it entirely, `dxt1` keeps it and decodes only the BC1 family in software. Unset is every BC format in software, which is the default on Adreno ([textures.md](textures.md)) |
| `PES17_FORCE_BC_HW=1` | | on an Adreno device: leave BC sampling to the driver rather than the software decoder |
| `PES17_FORCE_BC_DECOMPRESS=1` | | decode every BC texture in software on any device, which is how a desktop driver is made to exercise the decoder the Snapdragon path uses |
| `PES17_RECORD_THREAD=0` | | makes the Vulkan calls on the ring consumer instead of the recorder thread |
| `PES17_TIME_RENDER=1` | | where the ring consumer's time goes, and how long it waits on the GPU |
| `PES17_TRACE_PACING=<file>` | | a timeline written at exit: the consumer's batches and swaps, the title's presents, and every guest thread's engine waits, kernel waits and sleeps with their callers |
| `PES17_STUTTERS=1` | | (at the console's pacing only, not under `PES17_FPS_LIMIT`) from the first 30 consecutive match frames on, logs `STUTTER` for every match frame 25 ms or more after the previous one (the match state comes from the title's `Match/...` procedure, not the draw-based world test), with what the renderer spent the gap on, and `DISPLAY` when a frame is held or dropped by the window; totals at exit. When play stops for a second it logs `STALL` with what every guest thread waits on and every thread's stack (Linux), once a stall. The pad's Y and `F7` log a `STUTTER MARK` |

## Textures, store and telemetry

| switch | effect |
|---|---|
| `PES17_BC_ASTC` | where the GPU samples ASTC 4x4 LDR and does not sample BC, BC1/BC2/BC3 are converted to ASTC 4x4 instead of RGBA8: a quarter of the memory and of the bytes staged. It is on by default on those devices; `PES17_BC1_ASTC` is the same switch under its older name, and setting either is the same as leaving it |
| `PES17_NO_BC_ASTC=1` | turns that conversion off, leaving the software decode to RGBA8. This is the side to use in an A/B of the conversion |
| `PES17_TEXTURE_STORE=<file>` | the file the converted textures are kept in between runs. The app sets `<internal files>/texture_store.bin`; unset leaves the store closed, and it is only written while BC to ASTC is on ([textures.md](textures.md)) |
| `PES17_TEXTURE_STORE_MB=<n>` | the store's cap, 1024 MB. It stops adding records past it; what is already there still reads back |
| `PES17_TEXTURE_TIMING=1` | per-texture conversion and decode time, in the log's `tex:` lines |
| `PES17_TELEMETRY=0` | turns off the once-a-second telemetry CSV. It is on by default in a build that has it |

The telemetry file is
`/sdcard/Android/data/com.pes2018.recomp/files/telemetry.csv`, beside
`env_override`; the file it is rotated to is `telemetry.csv.1`. It is one row a
second, comma separated, after two comment lines and a row header:

```
# v1 ...
# resolved: texture_budget_mb=..., bc_astc=..., store=..., store_mb=..., msaa=..., scene_scale=..., fps_limit=..., hint=..., audio_burst=..., arena_mb=...
boot_ms,wall_ms,run_s,frames,ft_p50_us,ft_p95_us,ft_p99_us,ft_max_us,jank20,jank33,jank50,jank100,draws,presents,...
```

The first line names the format. `# resolved` is what the run settled on, so it
is where to look when the question is whether a switch arrived; the row header
names the columns. `frames` is a second's frames, `ft_*_us` are microseconds,
`draws` and `presents` are that second's counts, and the jank columns count
frames past 20, 33, 50 and 100 ms. A later build may add columns, so a reader
goes by the name and tolerates one that is absent; `tools/device_ab.sh` and
`tools/ab_window.sh` use `evict_n`, `evict_mb`, `au_missing`, `store_hit`,
`store_miss`, `thermal` and `armed` where the build writes them, and say a
column is absent rather than reading it as zero. `armed=0`, no rows, or no
`# resolved` line rejects the run.

## Frames and captures

| switch | effect |
|---|---|
| `PES17_DUMP_FRAMES=<dir>` | finished frames as PNM, every `PES17_DUMP_FRAME_EVERY` frames (600); `PES17_DUMP_FRAMES_AFTER_SECONDS` starts later, `PES17_DUMP_WORLD_FRAMES=1` keeps only frames that drew the world |
| `PES17_DUMP_TARGETS=1` | every colour surface beside the presented frame |
| `F7` | with the window open: marks a flash in the log. `PES17_FLASH_FRAMES=<n>` keeps the last n frames and writes them at each mark into `PES17_FLASH_DIR` (`diagnosis/flashes`). `PES17_FLASH_HUNT=1` (or `PES17_FLASH_DRAWS=1`) also writes every draw of the kept frames as `draws_rNNNNNN.txt`; `PES17_FLASH_HUNT=1` makes the pad's Y a mark and logs every texture drawn empty. `PES17_MARK_EMPTY=1` paints those magenta. `PES17_FLASH_MARK_AT=<seconds>` makes a mark nobody pressed. `tools/flash_hunt.sh` sets this up |
| `PES17_RENDERDOC=<seconds>[:<frames>]` | captures that many world frames (4) once the run is that old; `PES17_RENDERDOC_OUT` names the file prefix. RenderDoc must be injected before the Vulkan instance exists: run under `tools/capture_by_hand.sh` or `renderdoccmd capture` |
| `F10`, `F11` | with the window open: F10 captures the next four world frames; F11 captures every frame until pressed again (at most 120). The window runs a frame behind, so the picture in capture N was rendered in frame N-1 |
| validation | `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` enables the validation layer; its messages go to the log as `validation error:` / `validation warning:` lines, counted at exit. Add `VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT` for hazards between submissions |
