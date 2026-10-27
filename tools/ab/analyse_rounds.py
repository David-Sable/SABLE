# Summarises saved A/B rounds (MATH §30.6). Input: the SableAB logcat lines (adb logcat -v raw -s SableAB after --es cmd abrounds).
# Usage: python tools/ab/analyse_rounds.py rounds.txt
# Rounds saved before 0.9.4 may start with leftovers of the previous queue; the last 8 items are the round itself.
import json, sys, math
raw = open(sys.argv[1], encoding='utf-8').read().split('\n')
recs, cur = [], ''
for l in raw:
    l = l.rstrip('\r')
    if l.startswith('{"time"'):
        if cur: recs.append(cur)
        cur = l
    else: cur += l
if cur: recs.append(cur)
rounds = [json.loads(r) for r in recs]
rows = []
for r in rounds:
    its = r['items'][-8:]  # the round's own 4 changes (earlier entries are leftovers of the previous queue)
    pairs = [(its[i], its[i+1]) for i in range(0, 8, 2)]
    assert all(a['part'] == 'ending' and b['part'] == 'opening' for a, b in pairs), r['pick']
    def jumps(c):  # loudness step at each change: next song's opening vs this song's ending, after gain
        return [(b['openLufs'] + b[c]) - (a['endLufs'] + a[c]) for a, b in pairs]
    def anchor_steps(c):  # step between the songs' main sections after gain
        return [(b['anchorLufs'] + b[c]) - (a['anchorLufs'] + a[c]) for a, b in pairs]
    J = {c: jumps(c) for c in ('sable', 'plain', 'off')}
    A = {c: anchor_steps(c) for c in ('sable', 'plain', 'off')}
    rms = lambda v: math.sqrt(sum(x*x for x in v) / len(v))
    rows.append((r['pick'], {c: rms(J[c]) for c in J}, {c: rms(A[c]) for c in A}, max(abs(x) for x in [ (pb['sable']-pa['sable'])-(pb['plain']-pa['plain']) for pa,pb in pairs])))
    print(f"pick {r['pick']:6} | rms jump at change S {rms(J['sable']):4.1f} P {rms(J['plain']):4.1f} O {rms(J['off']):4.1f} | rms main-section step S {rms(A['sable']):4.1f} P {rms(A['plain']):4.1f} O {rms(A['off']):4.1f} | max S-vs-P diff {rows[-1][3]:4.1f}")
from collections import Counter
print(Counter(p for p, *_ in rows))
for pick in ('sable', 'plain'):
    sel = [x for x in rows if x[0] == pick]
    if sel: print(pick, 'mean max S-vs-P diff', sum(x[3] for x in sel)/len(sel), 'mean rms main step S-P', sum(x[2]['sable']-x[2]['plain'] for x in sel)/len(sel), 'mean rms jump S-P', sum(x[1]['sable']-x[1]['plain'] for x in sel)/len(sel))
