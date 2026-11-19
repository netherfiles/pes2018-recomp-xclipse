#!/bin/bash
# State-based A/B of env settings on a device: tools/device_ab.sh base PES17_X=1 base PES17_X=1
# DEVICE=<adb serial> MACRO=<macro name> (default play) SCRATCH=<dir for the copied runs>
#
# Each argument is one run: `base` (no env_override) or one or more NAME=value lines.
# A multi-line config is one argument, quoted:
#   tools/device_ab.sh base $'PES17_NO_BC_ASTC=1\nPES17_TEXTURE_STORE_MB=512' ...
# TEMPLATE=<file> is written before every run's config, so a shared three-line
# baseline lives in a file instead of being retyped; a later line wins.
#
# Runs are positional pairs for the report: run 1 vs 2, 3 vs 4, ... (A/B/A/B).
# A run is rejected when it never reached Match/MatchIdle, when telemetry.csv has
# no rows or no "# resolved" line, or when armed=0. A pair is rejected when the
# match windows' median draws differ by more than 5%.
S=${SCRATCH:-/tmp/pes17_ab}; mkdir -p "$S"
D=${DEVICE:?set DEVICE to the adb serial}; MACRO=${MACRO:-play}
B=com.pes2018.recomp; ACT=$B/com.pes2017.recomp.MainActivity; F=/sdcard/Android/data/$B/files
if [ -n "${TEMPLATE:-}" ] && [ ! -r "$TEMPLATE" ]; then
  echo "device_ab: TEMPLATE not readable: $TEMPLATE" >&2; exit 2
fi
if ! adb -s "$D" shell pm path "$B" 2>/dev/null | grep -q .; then
  echo "device_ab: $B is not installed on $D (or adb cannot see it)" >&2; exit 2
fi
RESULTS=$S/runs.jsonl; : > "$RESULTS"

do_run() {
  local cfg=$1 RUN=$2
  for attempt in 1 2 3; do
    adb -s "$D" shell am force-stop "$B"; sleep 2
    if [ -n "${TEMPLATE:-}" ] || [ "$cfg" != base ]; then
      {
        if [ -n "${TEMPLATE:-}" ]; then cat "$TEMPLATE"; fi
        if [ "$cfg" != base ]; then printf '%s\n' "$cfg"; fi
      } | adb -s "$D" shell "cat > $F/env_override"
    else
      adb -s "$D" shell "rm -f $F/env_override"
    fi
    # The CSV is per run: an empty one must not be answered with the last run's rows.
    adb -s "$D" shell "rm -f $F/telemetry.csv $F/telemetry.csv.1"
    adb -s "$D" shell am start -n "$ACT" >/dev/null
    for n in $(seq 180); do
      adb -s "$D" exec-out "tail -n 300 $F/game.log" 2>/dev/null | grep -q PreStartup && break
      sleep 1
    done
    sleep 2
    python3 - "$S" "$D" "$F" "$S/stream.txt" <<'PY' &
import subprocess,sys,time,re
S,D,F,out_path=sys.argv[1:5]
p=subprocess.Popen(['adb','-s',D,'exec-out',f'tail -n 0 -f {F}/game.log'],stdout=subprocess.PIPE,text=True,errors='replace',bufsize=1)
t0=time.time(); out=open(out_path,'w')
for line in p.stdout:
    if re.search(r'procedure:|Match/',line): out.write(f'{time.time()-t0:7.1f} {line[:150]}'); out.flush()
    if time.time()-t0>190: break
p.kill()
PY
    sleep 1
    adb -s "$D" shell am broadcast -a com.pes2017.recomp.MACRO -p "$B" --es cmd play --es name "$MACRO" >/dev/null
    ok=0
    for n in $(seq 150); do
      awk '$1>5 && /Match\/MatchIdle/{f=1} END{exit !f}' "$S/stream.txt" 2>/dev/null && { ok=1; break; }
      sleep 1
    done
    idle_wc=""
    if [ "$ok" = 1 ]; then
      # Anchor the report at the second the match started: the CSV row count there,
      # not a guess from wall-clock times.
      idle_wc=$(adb -s "$D" shell "wc -l < $F/telemetry.csv" 2>/dev/null | tr -d '\r ')
      sleep 68
    fi
    pkill -f "tail -n 0 -f" ; wait
    rm -f "$S/run$RUN.csv.1" "$S/run$RUN.csv"
    adb -s "$D" pull "$F/telemetry.csv.1" "$S/run$RUN.csv.1" >/dev/null 2>&1 ||
      adb -s "$D" exec-out "cat $F/telemetry.csv.1" > "$S/run$RUN.csv.1" 2>/dev/null
    adb -s "$D" pull "$F/telemetry.csv" "$S/run$RUN.csv" >/dev/null 2>&1 ||
      adb -s "$D" exec-out "cat $F/telemetry.csv" > "$S/run$RUN.csv" 2>/dev/null
    python3 - "$cfg" "$RUN" "$S/run$RUN.csv.1" "$S/run$RUN.csv" "$idle_wc" "$ok" "$S/run$RUN.json" <<'PY'
import json, re, statistics as st, sys

label, run, csv_prev, csv_now, idle_wc, reached, out_json = sys.argv[1:8]

def parse(path):
    resolved, header, rows, lines = {}, None, [], 0
    try:
        f = open(path, errors="replace")
    except OSError:
        return resolved, None, [], 0
    with f:
        for raw in f:
            line = raw.rstrip("\r\n").lstrip("\ufeff")
            if not line.strip():
                continue
            lines += 1
            if line.startswith("#"):
                m = re.match(r"#\s*resolved:\s*(.*)", line)
                if m:
                    for part in m.group(1).split(","):
                        k, eq, v = part.strip().partition("=")
                        if eq and k:
                            resolved[k.strip()] = v.strip()
                continue
            if header is None:
                header = [h.strip().lstrip("\ufeff") for h in line.split(",")]
                continue
            rows.append(dict(zip(header, [v.strip() for v in line.split(",")])))
    return resolved, header, rows, lines

r1, h1, a, n1 = parse(csv_prev)
r2, h2, b, n2 = parse(csv_now)
resolved = dict(r1); resolved.update(r2)
header = h2 or h1 or []
rows = a + b
hdr_now = n2 - len(b)          # comment lines + the row header in telemetry.csv

def first(*names):
    return next((k for k in names if k in header), None)
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

anchored = idle_wc.strip().isdigit()
start = 0
if anchored:
    start = len(a) + max(0, int(idle_wc) - hdr_now)
    if start > len(rows): anchored, start = False, 0
win = rows[start:] if anchored else rows
intro, match = win[:25], win[25:65]

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
    fv = fps_of(rs)
    hit, miss = store_pair(rs)
    return {
        "n": len(rs), "fps": med(fv),
        "p50": ms(med(nums(rs, p50k))),
        "p95": ms(med(nums(rs, first("ft_p95_us")))),
        "p99": ms(med(nums(rs, first("ft_p99_us")))),
        "max": ms(mx(nums(rs, first("ft_max_us")))),
        "jank33": total(rs, first("jank33")),
        "jank100": total(rs, first("jank100")),
        "draws": med(nums(rs, first("draws"))),
        "presents": med(nums(rs, first("presents"))),
        "evict_n": total(rs, first("evict_n")),
        "evict_mb": total(rs, first("evict_mb")),
        "au_missing": total(rs, first("au_missing")),
        "store_hit": hit, "store_miss": miss,
        "trim": trim(rs), "thermal": thermal(rs),
    }
I, M = window(intro), window(match)

def f1(v): return "%.1f" % v if isinstance(v, (int, float)) else "n/a"
def i0(v): return "%.0f" % v if isinstance(v, (int, float)) else "n/a"
def line(name, w):
    if not w["n"]: return "   %-13s no rows" % name
    return ("   %-13s fps %s p50 %s p95 %s p99 %s max %s ms | jank33 %s jank100 %s | "
            "draws %s presents %s | evict %s/%s MB au_missing %s | store %s/%s | thermal %s | n=%d"
            % (name, f1(w["fps"]), f1(w["p50"]), f1(w["p95"]), f1(w["p99"]), f1(w["max"]),
               i0(w["jank33"]), i0(w["jank100"]), i0(w["draws"]), i0(w["presents"]),
               i0(w["evict_n"]), f1(w["evict_mb"]), i0(w["au_missing"]),
               i0(w["store_hit"]), i0(w["store_miss"]), w["thermal"] or "n/a", w["n"]))

shown = label.replace("\n", "; ")[:48]
head = "run %d  %-48s matchIdle=%s rows=%d" % (int(run), shown, reached, len(rows))
if problems:
    print(head + "  REJECT: " + "; ".join(problems))
else:
    print(head + "  window=" + ("idle+" if anchored else "whole run (not anchored)"))
    print(line("intro 0-25s", I))
    print(line("match 25-65s", M))
    if len(win) < 65: print("   short window: %d rows after idle" % len(win))
    keys = ("texture_budget_mb", "bc_astc", "store", "store_mb", "msaa", "scene_scale",
            "fps_limit", "hint", "audio_burst", "arena_mb")
    print("   resolved: " + " ".join("%s=%s" % (k, resolved[k]) for k in keys if k in resolved))
if armed is None and not problems:
    print("   armed: no value in this build (not checked)")
with open(out_json, "w") as f:
    json.dump({"run": int(run), "label": label, "valid": not problems, "problems": problems,
               "resolved": resolved, "intro": I, "match": M, "rows": len(rows),
               "start": start, "anchored": anchored, "armed": armed, "reached": reached}, f)
sys.exit(2 if problems else 0)
PY
    [ $? -eq 0 ] && return 0
  done
  return 1
}

RUN=0; FAILED=0
for cfg in "$@"; do
  RUN=$((RUN + 1))
  if do_run "$cfg" "$RUN"; then
    cat "$S/run$RUN.json" >> "$RESULTS"; echo >> "$RESULTS"
  else
    FAILED=1
    [ -f "$S/run$RUN.json" ] && { cat "$S/run$RUN.json" >> "$RESULTS"; echo >> "$RESULTS"; }
  fi
done

if [ -s "$RESULTS" ]; then
  python3 - "$RESULTS" <<'PY'
import json, sys

runs = [json.loads(l) for l in open(sys.argv[1], errors="replace") if l.strip()]

def f1(v): return "%.1f" % v if isinstance(v, (int, float)) else "n/a"
def i0(v): return "%.0f" % v if isinstance(v, (int, float)) else "n/a"
def delta(a, b, k, unit="", fmt="%.1f", dfmt="%+.1f"):
    x, y = a.get(k), b.get(k)
    if not isinstance(x, (int, float)) or not isinstance(y, (int, float)): return "%s n/a" % k
    return "%s %s -> %s (B-A %s%s)" % (k, fmt % x, fmt % y, dfmt % (y - x), unit)

def scene(a, b):
    if any("n" in r["match"] and not r["match"].get("n") for r in (a, b)):
        return False, "match window empty"
    x, y = a["match"].get("draws"), b["match"].get("draws")
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
        return False, "resolved config identical -- the switch did not reach the run"
    note = ", ".join("%s %s->%s" % (k, x, y) for k, (x, y) in diff.items())
    knobs = set(diff)
    fired, missing, counters = False, False, []
    def act(r, k):                 # the store and the evictor work during the intro too
        v = [r["intro"].get(k), r["match"].get(k)]
        v = [x for x in v if isinstance(x, (int, float))]
        return sum(v) if v else None
    if knobs & {"bc_astc", "store", "store_mb"}:
        sh, sm = act(a, "store_hit"), act(a, "store_miss")
        bh, bm = act(b, "store_hit"), act(b, "store_miss")
        if sh is None and sm is None and bh is None and bm is None:
            missing = True; counters.append("store columns absent")
        else:
            hits, miss = (sh or 0) + (bh or 0), (sm or 0) + (bm or 0)
            counters.append("store hits/misses %.0f/%.0f" % (hits, miss))
            if hits + miss > 0: fired = True
    if "texture_budget_mb" in knobs:
        ev = (act(a, "evict_n"), act(b, "evict_n"))
        tr = (act(a, "trim"), act(b, "trim"))
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

by = {r["run"]: r for r in runs}
if by and max(by) % 2: print("note: run %d has no pair" % max(by))
for n in range(1, (max(by) if by else 0) + 1, 2):
    a, b = by.get(n), by.get(n + 1)
    if not a or not b:
        print("pair %d: incomplete (run missing)" % ((n + 1) // 2))
        continue
    pairno = (n + 1) // 2
    if a["label"] == b["label"]:
        print("pair %d: same config (%s), skipped" % (pairno, a["label"].replace("\n", "; ")[:40]))
        continue
    print("pair %d: %s -> %s" % (pairno, a["label"].replace("\n", "; ")[:40],
                                 b["label"].replace("\n", "; ")[:40]))
    bad = [r for r in (a, b) if not r["valid"]]
    if bad:
        print("   inconclusive: run %d rejected (%s)" %
              (bad[0]["run"], "; ".join(bad[0]["problems"])))
        continue
    ok, text = scene(a, b)
    print("   scene: " + text + ("" if ok is None else (" -- within 5%" if ok else " -- MISMATCH")))
    if ok is False:
        print("   inconclusive: scene mismatch")
        continue
    fired, why = mechanism(a, b)
    fa, fb = a["match"].get("fps"), b["match"].get("fps")
    rel = " (%+.1f%%)" % ((fb - fa) / fa * 100) if isinstance(fa, (int, float)) and fa else ""
    print("   " + delta(a["match"], b["match"], "fps") + rel)
    print("   " + "; ".join((delta(a["match"], b["match"], "p50", " ms"),
                             delta(a["match"], b["match"], "p95", " ms"),
                             delta(a["match"], b["match"], "p99", " ms"),
                             delta(a["match"], b["match"], "max", " ms"))))
    print("   " + "; ".join((delta(a["match"], b["match"], "jank33", "", "%.0f", "%+.0f"),
                             delta(a["match"], b["match"], "jank100", "", "%.0f", "%+.0f"),
                             delta(a["match"], b["match"], "draws", "", "%.0f", "%+.0f"))))
    print("   mechanism: " + why)
    if not fired:
        print("   inconclusive: mechanism did not fire")
    elif ok is None:
        print("   note: scene match not checked (%s)" % text)
PY
fi

adb -s "$D" shell "rm -f $F/env_override"; adb -s "$D" shell am force-stop "$B"
exit "$FAILED"
