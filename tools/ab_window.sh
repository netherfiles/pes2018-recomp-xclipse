#!/bin/bash
# One launch + macro + a fixed measuring window, with the numbers that say where the time went:
#   tools/ab_window.sh <label> [PES17_NAME=value ...]
#   tools/ab_window.sh <label-a> [PES17_NAME=value ...] -- <label-b> [PES17_NAME=value ...]
#
# The second form runs A/B/A/B for PAIRS pairs (default 3), then prints each pair's
# sign and magnitude. A pair is inconclusive when the two windows' median draws are
# more than 5% apart, when a run was rejected, when the resolved values are
# identical, or when a store/budget change has no store or eviction activity.
# Needs ANDROID_SERIAL (adb serial; check `adb shell getprop ro.product.model` first
# -- two phones are used here and a build once went onto the wrong one) and
# files/macros/.remote_control.
#
#   MACRO=<name>   macro to replay (default play)
#   W0=<s>         seconds after playback starts when the window opens (default: after a 114 s macro)
#   MEASURE=<s>    window length (default 80)
#   PAIRS=<n>      pairs in the two-arm form (default 3; fewer cannot show a sign)
#   SHOT=1         also take a screenshot a third of the way into the window
#   OUT=<dir>      where <label>.log/.mark/... go (default /tmp/pes17_ab); the two-arm
#                  form adds .p<N> to each run and writes <a>-vs-<b>.runs.jsonl
#
# Prints: median fps of the window, p50/p95/p99/max frame time, jank33/jank100,
# draws, evictions, the texture store's hits and misses, thermal state, per-thread
# CPU (from /proc/<pid>/task/*/stat: utime+stime, so the run-queue wait that
# schedstat gives is not there), GPU pass time and draws a frame.
#
# Statistics come from files/telemetry.csv (1 Hz, header named) and its .1, not
# from the repeat-compressed game log. A run with no rows, no "# resolved" line,
# or armed=0 is rejected. The CSV's draws column is what makes two windows
# comparable; the log's draws/frame line is still printed as a cross-check.
#
# Why a window and not the whole run: macros are time-based, the scene at the end differs from
# run to run (300-600 draws a frame apart), and heat skews later runs. Interleave configs
# (A B A B), repeat, and judge differences by scene-matched windows only (tools/crowd_windows.py
# does that for PES17_CROWD_ALTERNATE).
LABEL=${1:?label}; shift
ENV_A=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do ENV_A+=("$1"); shift; done
LABEL_B=""; ENV_B=()
if [ "${1:-}" = "--" ]; then
  shift
  LABEL_B=${1:?label after --}; shift
  while [ $# -gt 0 ]; do ENV_B+=("$1"); shift; done
fi
PKG=com.pes2018.recomp
ACT=$PKG/com.pes2017.recomp.MainActivity
F=/sdcard/Android/data/$PKG/files
OUT=${OUT:-/tmp/pes17_ab}; mkdir -p "$OUT"
MEASURE=${MEASURE:-80}
W0=${W0:-125}
PAIRS=${PAIRS:-3}
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
MULTI=0; [ -n "$LABEL_B" ] && MULTI=1
[ "$MULTI" = 1 ] && [ "$LABEL" = "$LABEL_B" ] && {
  echo "ab_window: the two arm labels must differ" >&2; exit 2
}
case "$PAIRS" in ''|*[!0-9]*) echo "ab_window: PAIRS must be a number" >&2; exit 2;; esac
[ "$PAIRS" -ge 1 ] || { echo "ab_window: PAIRS must be at least 1" >&2; exit 2; }
if ! adb shell pm path "$PKG" 2>/dev/null | grep -q .; then
  echo "ab_window: $PKG is not installed (or adb cannot see the device)" >&2; exit 2
fi
if [ "$MULTI" = 1 ]; then
  SAFE=${LABEL//\//_}-vs-${LABEL_B//\//_}
  RUNS="$OUT/$SAFE.runs.jsonl"; : > "$RUNS"
  [ "$PAIRS" -lt 3 ] && echo "note: PAIRS=$PAIRS; fewer than 3 pairs cannot show a consistent sign"
fi

sleep_until() { while [ $(( $(date +%s) - START )) -lt "$1" ]; do sleep 1; done; }
snap() { adb shell "for t in /proc/$GP/task/*; do echo \"\$(cat \$t/stat)\"; done"; }
clk() { adb shell 'for c in 0 3 4 7; do printf "cpu$c=%s " $(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq); done'; }

run_one() {
  local label=$1 pair=$2; shift 2
  local suffix=""; [ "$MULTI" = 1 ] && suffix=".p$pair"
  local base="$OUT/$label$suffix"
  local json="$base.json"; rm -f "$json"
  adb shell "rm -f $F/env_override"
  if [ $# -gt 0 ]; then printf '%s\n' "$@" | adb shell "cat > $F/env_override"; fi
  adb shell am force-stop "$PKG"
  adb shell "rm -f $F/game.log $F/telemetry.csv $F/telemetry.csv.1"
  adb logcat -c
  adb shell am start -n "$ACT" >/dev/null

  for i in $(seq 1 60); do      # the guest reaching PreStartup, then a moment more
    sleep 2
    adb shell "grep -q 'procedure: PreStartup' $F/game.log" 2>/dev/null && break
  done
  sleep 3
  adb shell am broadcast -a com.pes2017.recomp.MACRO -p "$PKG" --es cmd play --es name "${MACRO:-play}" >/dev/null
  START=$(date +%s)

  sleep_until "$W0"
  local reached=$(adb shell "grep -c 'Match/MatchIdle' $F/game.log" | tr -d '\r')
  echo "$(adb shell "wc -l < $F/game.log" | tr -d '\r ')" > "$base.mark"
  local tstart=$(adb shell "wc -l < $F/telemetry.csv" 2>/dev/null | tr -d '\r ')
  GP=$(adb shell pidof "$PKG:game" | tr -d '\r ')       # the run lives in its own process
  snap > "$base.ss1"; local SS1T=$(date +%s)
  adb shell "for tid in \$(ls /proc/$GP/task); do n=\$(cat /proc/$GP/task/\$tid/comm); case \"\$n\" in pes17|'gpu ring consum'|'vulkan presente'|'guest thread 35') echo \"\$n \$(grep Cpus_allowed_list /proc/$GP/task/\$tid/status | cut -f2)\";; esac; done" > "$base.aff"
  sleep_until $((W0 + MEASURE / 3))
  clk > "$base.clk1"
  [ -n "$SHOT" ] && adb exec-out screencap -p > "$base.png"
  sleep_until $((W0 + 2 * MEASURE / 3))
  clk > "$base.clk2"
  sleep_until $((W0 + MEASURE))
  snap > "$base.ss2"; echo $(( $(date +%s) - SS1T )) > "$base.sst"
  local tend=$(adb shell "wc -l < $F/telemetry.csv" 2>/dev/null | tr -d '\r ')
  TEMP=$(adb shell dumpsys battery | awk '/temperature/{print $2/10}')
  # The SoC's own sensor and the GPU's cooling level: Android's overall thermal status follows the
  # skin and stays 0 while these say severe (X7 Pro: CPU/GPU 85-86 C, cooling level 27).
  adb shell dumpsys thermalservice | awk '/Current temperatures from HAL/{f=1} f&&/mName=CPU/{print; exit}' | sed -E 's/.*mValue=([0-9.]+).*/\1/' > "$base.cputemp"
  adb shell dumpsys thermalservice | grep -m1 "devfreq-13000000.mali" | sed -E 's/.*mValue=([0-9]+).*/\1/' > "$base.malicool"
  adb shell cat "$F/game.log" > "$base.log"
  adb logcat -d -b crash > "$base.crash"
  rm -f "$base.csv" "$base.csv.1"
  adb pull "$F/telemetry.csv.1" "$base.csv.1" >/dev/null 2>&1 ||
    adb exec-out "cat $F/telemetry.csv.1" > "$base.csv.1" 2>/dev/null
  adb pull "$F/telemetry.csv" "$base.csv" >/dev/null 2>&1 ||
    adb exec-out "cat $F/telemetry.csv" > "$base.csv" 2>/dev/null

  python3 - "$label" "$pair" "$reached" "$TEMP" "$base" "$tstart" "$tend" "$json" "$MEASURE" <<'EOF'
import json, re, statistics as st, sys
label, pair, reached, temp, base, tstart, tend, out_json, measure = sys.argv[1:10]

def parse(path):
    resolved, header, rows, lines = {}, None, [], 0
    try: f = open(path, errors="replace")
    except OSError: return resolved, None, [], 0
    with f:
        for raw in f:
            line = raw.rstrip("\r\n").lstrip("\ufeff")
            if not line.strip(): continue
            lines += 1
            if line.startswith("#"):
                m = re.match(r"#\s*resolved:\s*(.*)", line)
                if m:
                    for part in m.group(1).split(","):
                        k, eq, v = part.strip().partition("=")
                        if eq and k: resolved[k.strip()] = v.strip()
                continue
            if header is None:
                header = [h.strip().lstrip("\ufeff") for h in line.split(",")]
                continue
            rows.append(dict(zip(header, [v.strip() for v in line.split(",")])))
    return resolved, header, rows, lines

r1, h1, a, n1 = parse(base + ".csv.1")
r2, h2, b, n2 = parse(base + ".csv")
resolved = dict(r1); resolved.update(r2)
header = h2 or h1 or []
rows = a + b
hdr_now = n2 - len(b)          # comment lines + the row header in telemetry.csv

def first(*names): return next((k for k in names if k in header), None)
def nums(rs, key):
    out = []
    if not key: return out
    for r in rs:
        v = (r.get(key) or "").strip()
        if not v: continue
        try: out.append(float(v))
        except ValueError: pass
    return out
def med(v): return st.median(v) if v else None
def mx(v): return max(v) if v else None
def total(rs, key):
    v = nums(rs, key)
    return sum(v) if v else None
def ms(v): return v / 1000.0 if v is not None else None
def truthy(v): return (v or "").strip().lower() not in ("", "0", "0.0", "false", "no")

armed = None
if "armed" in header:
    filled = [(r.get("armed") or "").strip() for r in rows]
    filled = [v for v in filled if v]
    if filled: armed = any(truthy(v) for v in filled)
elif "armed" in resolved:
    v = (resolved.get("armed") or "").strip()
    if v: armed = truthy(v)
problems = []
if not rows: problems.append("no data rows")
if not resolved: problems.append("no '# resolved' header")
if armed is False: problems.append("armed=0")
if reached == "0": problems.append("never reached Match/MatchIdle")

bounds = tstart.strip().isdigit() and tend.strip().isdigit()
anchored = False; start = end = 0; win = rows; window_problem = None
if bounds:
    start = len(a) + max(0, int(tstart) - hdr_now)
    end = len(a) + max(0, int(tend) - hdr_now)
    start, end = min(start, len(rows)), min(end, len(rows))
    if start < end:
        anchored = True; win = rows[start:end]
    else:
        window_problem = "no telemetry rows in the measurement window"
        problems.append(window_problem)

fk, p50k = first("frames"), first("ft_p50_us")
def fps_of(rs):
    out = []
    for r in rs:
        f = None
        if fk:
            try: f = float(r.get(fk, ""))
            except ValueError: f = None
        if (f is None or f <= 0) and p50k:
            try:
                p = float(r.get(p50k, ""))
                if p > 0: f = 1e6 / p
            except ValueError: pass
        if f is not None and f > 0: out.append(f)
    return out
def store_pair(rs):
    hit, miss = total(rs, first("store_hit", "store_hits")), total(rs, first("store_miss", "store_misses"))
    combo = first("store_hit/miss", "store_hit_miss")
    if combo and (hit is None or miss is None):
        h = m = 0.0; seen = False
        for r in rs:
            v = (r.get(combo) or "").strip()
            if "/" not in v: continue
            x, y = v.split("/", 1)
            try: h += float(x); m += float(y); seen = True
            except ValueError: pass
        if seen: hit, miss = h, m
    return hit, miss
def thermal(rs):
    k = first("thermal")
    if not k: return None
    vals = [(r.get(k) or "").strip() for r in rs if (r.get(k) or "").strip()]
    if not vals: return None
    try: return "%.0f" % med([float(v) for v in vals])
    except ValueError: return vals[-1]
def trim(rs):
    k = first("trim", "budget_trim", "trim_n", "budget_trim_n", "trim_mb", "budget_trim_mb")
    return total(rs, k) if k else None
def window(rs):
    hit, miss = store_pair(rs)
    frames = total(rs, fk)
    return {
        "n": len(rs), "fps": med(fps_of(rs)), "frames": frames,
        "p50": ms(med(nums(rs, p50k))),
        "p95": ms(med(nums(rs, first("ft_p95_us")))),
        "p99": ms(med(nums(rs, first("ft_p99_us")))),
        "max": ms(mx(nums(rs, first("ft_max_us")))),
        "jank33": total(rs, first("jank33")), "jank100": total(rs, first("jank100")),
        "draws": med(nums(rs, first("draws"))), "presents": med(nums(rs, first("presents"))),
        "evict_n": total(rs, first("evict_n")), "evict_mb": total(rs, first("evict_mb")),
        "au_missing": total(rs, first("au_missing")),
        "store_hit": hit, "store_miss": miss, "trim": trim(rs), "thermal": thermal(rs),
    }
S = window(win)
if not problems and S["fps"] is None:
    problems.append("no fps in telemetry (frames and ft_p50_us)")
try: crash = open(base + ".crash", errors="replace").read().strip()
except OSError: crash = ""

def f1(v): return "%.1f" % v if isinstance(v, (int, float)) else "n/a"
def i0(v): return "%.0f" % v if isinstance(v, (int, float)) else "n/a"
def jank(v, frames):
    if not isinstance(v, (int, float)): return "n/a"
    return i0(v) if not frames else "%s (%.1f/1k)" % (i0(v), v / frames * 1000)
shown = label.replace("\n", "; ")[:40]
head = "%s%s: matchIdle=%s" % (shown, (".p%d" % int(pair)) if pair not in ("", "0") else "", reached)
if problems:
    print(head + "  REJECT: " + "; ".join(problems))
else:
    print(head + " fps=%s p50=%s p95=%s p99=%s max=%s ms jank33=%s jank100=%s draws=%s "
          "evict=%s/%sMB au_missing=%s store=%s/%s thermal=%s temp=%sC crash=%s"
          % (f1(S["fps"]), f1(S["p50"]), f1(S["p95"]), f1(S["p99"]), f1(S["max"]),
             jank(S["jank33"], S["frames"]), jank(S["jank100"], S["frames"]), i0(S["draws"]),
             i0(S["evict_n"]), f1(S["evict_mb"]), i0(S["au_missing"]),
             i0(S["store_hit"]), i0(S["store_miss"]), S["thermal"] or "n/a", temp, "YES" if crash else "no"))
    keys = ("texture_budget_mb", "bc_astc", "store", "store_mb", "msaa", "scene_scale",
            "fps_limit", "hint", "audio_burst", "arena_mb")
    print("   window=%s s rows=%d %s | resolved %s" %
          (measure, S["n"], "anchored" if anchored else "whole run (not anchored)",
           " ".join("%s=%s" % (k, resolved[k]) for k in keys if k in resolved)))
    if armed is None: print("   armed: no value in this build (not checked)")

try: lines = open(base + ".log", errors="replace").read().splitlines()
except OSError: lines = []
try:
    idx = int(open(base + ".mark").read().strip())
except (OSError, ValueError):
    idx = 0
gpuwait = [float(m.group(1)) for l in lines[idx:]
           for m in [re.search(r"([0-9.]+) ms a frame waiting for the GPU", l)] if m]
def rd(ext):
    try: return open(base + "." + ext, errors="replace").read().strip()
    except OSError: return ""
if not problems:
    print("   clocks: %s | %s   cpu_die_C=%s mali_cooling=%s" %
          (rd("clk1"), rd("clk2"), rd("cputemp"), rd("malicool")))

def ss(p):                       # comm has spaces and parens: split at the last ')'
    d = {}
    for l in open(p, errors="replace"):
        l = l.rstrip()
        if ")" not in l: continue
        tid = l.split(" ", 1)[0]; comm = l[l.index("(") + 1:l.rindex(")")]
        f = l[l.rindex(")") + 2:].split()
        if len(f) > 12: d[tid] = (comm, (int(f[11]) + int(f[12])) * 10_000_000)   # 10 ms ticks, as ns
    return d
if not problems:
    try:
        ss1, ss2 = ss(base + ".ss1"), ss(base + ".ss2"); dt = float(rd("sst"))
        pct = lambda name: sum(ss2[t][1] - ss1[t][1] for t in ss2 if t in ss1 and ss2[t][0] == name) / 1e9 / dt * 100
        guest = sorted(((ss2[t][1] - ss1[t][1]) / 1e9 / dt * 100 for t in ss2 if t in ss1 and ss2[t][0].startswith("guest thread")), reverse=True)[:3]
        total = sum(ss2[t][1] - ss1[t][1] for t in ss2 if t in ss1) / 1e9 / dt * 100
        print("   cpu%% of a core: main(pes17)=%.0f ring=%.0f recorder=%.0f presenter=%.0f guest_top3=%s process=%.0f"
              % (pct("pes17"), pct("gpu ring consum"), pct("recorder"), pct("vulkan presente"),
                 "/".join(f"{g:.0f}" for g in guest), total))
    except Exception as e:
        print("   cpu: n/a", e)
    gp = [l for l in lines[idx:] if "gpu: " in l and "ms of pass time" in l]
    print("   gpu passes:", gp[-1].split("gpu: ", 1)[1][:80] if gp else "none (add PES17_GPU_TIMING=1)")
    dv = [int(m.group(1)) / 600 for l in lines[idx:] if (m := re.search(r"swaps: (\d+) draws", l))]
    print("   log draws/frame median: %.0f" % (st.median(dv) if dv else -1))
    print("   affinity: " + "; ".join(rd("aff").split("\n")))
if gpuwait: print("   gpuwait max: %.1f ms (log)" % max(gpuwait))

with open(out_json, "w") as f:
    json.dump({"label": label, "pair": int(pair) if pair else 0, "valid": not problems,
               "problems": problems, "resolved": resolved, "stats": S, "rows": len(rows),
               "start": start, "end": end, "anchored": anchored, "armed": armed,
               "reached": reached, "measure": measure}, f)
sys.exit(2 if problems else 0)
EOF
  local rc=$?
  if [ "$MULTI" = 1 ] && [ -f "$json" ]; then cat "$json" >> "$RUNS"; echo >> "$RUNS"; fi
  return $rc
}

FAILED=0
if [ "$MULTI" = 0 ]; then
  run_one "$LABEL" "" "${ENV_A[@]}" || FAILED=1
else
  for p in $(seq 1 "$PAIRS"); do
    run_one "$LABEL" "$p" "${ENV_A[@]}" || FAILED=1
    run_one "$LABEL_B" "$p" "${ENV_B[@]}" || FAILED=1
  done
  python3 - "$RUNS" "$LABEL" "$LABEL_B" "$PAIRS" <<'EOF'
import json, sys
runs = [json.loads(l) for l in open(sys.argv[1], errors="replace") if l.strip()]
a_label, b_label, pairs = sys.argv[2], sys.argv[3], int(sys.argv[4])
by = {}
for r in runs: by[(r["pair"], r["label"])] = r

def f1(v): return "%.1f" % v if isinstance(v, (int, float)) else "n/a"
def i0(v): return "%.0f" % v if isinstance(v, (int, float)) else "n/a"
def delta(a, b, k, unit="", fmt="%.1f", dfmt="%+.1f"):
    x, y = a.get(k), b.get(k)
    if not isinstance(x, (int, float)) or not isinstance(y, (int, float)): return "%s n/a" % k
    return "%s %s -> %s (B-A %s%s)" % (k, fmt % x, fmt % y, dfmt % (y - x), unit)

def scene(a, b):
    x, y = a["stats"].get("draws"), b["stats"].get("draws")
    if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
        return None, "draws column absent"
    top = max(x, y)
    if top <= 0: return None, "draws 0"
    rel = abs(x - y) / top
    return rel <= 0.05, "draws %s vs %s (%.1f%%)" % (i0(x), i0(y), rel * 100)

def mechanism(a, b):
    ra, rb = a["resolved"], b["resolved"]
    diff = {k: (ra.get(k), rb.get(k)) for k in sorted(set(ra) | set(rb)) if ra.get(k) != rb.get(k)}
    if not diff:
        return False, "resolved config identical -- the switches did not reach the runs"
    note = ", ".join("%s %s->%s" % (k, x, y) for k, (x, y) in diff.items())
    knobs = set(diff); fired = missing = False; counters = []
    if knobs & {"bc_astc", "store", "store_mb"}:
        sh, sm = a["stats"].get("store_hit"), a["stats"].get("store_miss")
        bh, bm = b["stats"].get("store_hit"), b["stats"].get("store_miss")
        if sh is None and sm is None and bh is None and bm is None:
            missing = True; counters.append("store columns absent")
        else:
            hits, miss = (sh or 0) + (bh or 0), (sm or 0) + (bm or 0)
            counters.append("store hits/misses %.0f/%.0f" % (hits, miss))
            if hits + miss > 0: fired = True
    if "texture_budget_mb" in knobs:
        ev = (a["stats"].get("evict_n"), b["stats"].get("evict_n"))
        tr = (a["stats"].get("trim"), b["stats"].get("trim"))
        if ev == (None, None) and tr == (None, None):
            missing = True; counters.append("evict/trim columns absent")
        else:
            counters.append("evict_n %s/%s trim %s/%s" %
                            (i0(ev[0]), i0(ev[1]), i0(tr[0]), i0(tr[1])))
            if any(v is not None and v > 0 for v in ev + tr): fired = True
    if not (knobs & {"bc_astc", "store", "store_mb", "texture_budget_mb"}):
        return True, "fired (%s)" % note
    detail = note + "; " + ", ".join(counters)
    if fired: return True, "fired (%s)" % detail
    if missing: return False, "cannot confirm (%s)" % detail
    return False, "did not fire (%s)" % detail

good = []
for p in range(1, pairs + 1):
    a, b = by.get((p, a_label)), by.get((p, b_label))
    print("pair %d  %s vs %s" % (p, a_label, b_label))
    if not a or not b:
        print("   inconclusive: incomplete pair"); continue
    bad = [r for r in (a, b) if not r["valid"]]
    if bad:
        print("   inconclusive: run %s rejected (%s)" %
              (bad[0]["label"], "; ".join(bad[0]["problems"]))); continue
    ok, text = scene(a, b)
    print("   scene: " + text + ("" if ok is None else (" -- within 5%" if ok else " -- MISMATCH")))
    if ok is False:
        print("   inconclusive: scene mismatch"); continue
    fired, why = mechanism(a, b)
    sa, sb = a["stats"], b["stats"]
    fa, fb = sa.get("fps"), sb.get("fps")
    rel = " (%+.1f%%)" % ((fb - fa) / fa * 100) if isinstance(fa, (int, float)) and fa else ""
    print("   " + delta(sa, sb, "fps") + rel)
    print("   " + "; ".join((delta(sa, sb, "p50", " ms"), delta(sa, sb, "p95", " ms"),
                             delta(sa, sb, "p99", " ms"), delta(sa, sb, "max", " ms"))))
    print("   " + "; ".join((delta(sa, sb, "jank33", "", "%.0f", "%+.0f"),
                             delta(sa, sb, "jank100", "", "%.0f", "%+.0f"),
                             delta(sa, sb, "draws", "", "%.0f", "%+.0f"))))
    print("   evict %s/%s -> %s/%s MB | trim %s -> %s | au_missing %s -> %s | store %s/%s -> %s/%s | thermal %s -> %s"
          % (i0(sa.get("evict_n")), f1(sa.get("evict_mb")), i0(sb.get("evict_n")),
             f1(sb.get("evict_mb")), i0(sa.get("trim")), i0(sb.get("trim")),
             i0(sa.get("au_missing")), i0(sb.get("au_missing")),
             i0(sa.get("store_hit")), i0(sa.get("store_miss")),
             i0(sb.get("store_hit")), i0(sb.get("store_miss")),
             sa.get("thermal") or "n/a", sb.get("thermal") or "n/a"))
    print("   mechanism: " + why)
    if not fired:
        print("   inconclusive: mechanism did not fire")
    else:
        good.append((fb - fa) if isinstance(fa, (int, float)) and isinstance(fb, (int, float)) else None)
        if isinstance(fa, (int, float)) and isinstance(fb, (int, float)) and fa:
            print("   sign %+d, magnitude %+.1f%%" % (1 if fb > fa else (-1 if fb < fa else 0),
                                                      (fb - fa) / fa * 100))

vals = [v for v in good if v is not None]
if not vals:
    print("summary: no comparable pair")
else:
    lo = sum(1 for v in vals if v < 0); hi = sum(1 for v in vals if v > 0)
    med = sorted(vals)[len(vals) // 2] if len(vals) % 2 else sum(sorted(vals)[len(vals)//2-1:len(vals)//2+1]) / 2
    print("summary: %d pair(s), %d comparable; B higher in %d, lower in %d; median of pair deltas %+.1f fps%s"
          % (pairs, len(vals), hi, lo, med,
             " -- no single sign" if hi and lo else ""))
EOF
fi
exit "$FAILED"
