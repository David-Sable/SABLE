# Per-gesture frame figures from `dumpsys gfxinfo app.sable framestats` files: every frame of the gesture (a gesture is shorter
# than the 120 frames gfxinfo keeps), how many reached the screen late, and the slowest frame. The slowest frame is what the eye
# catches on a first visit to a tab; a share of late frames over many repeats hides it.
# python tab_frames.py name file [file ...]
import sys

name = sys.argv[1]
n = late = 0
worst = []
for f in sys.argv[2:]:
    lines = open(f, encoding='utf-8', errors='ignore').read().splitlines()
    i = [k for k, l in enumerate(lines) if l.startswith('Flags,')][0]
    idx = {h: j for j, h in enumerate(lines[i].split(',')) if h}
    top = 0.0
    for l in lines[i + 1:]:
        if not l.strip() or l.startswith('---'):
            break
        p = l.split(',')
        if p[0] != '0':
            continue
        n += 1
        late += int(p[idx['FrameCompleted']]) > int(p[idx['FrameDeadline']])
        top = max(top, (int(p[idx['FrameCompleted']]) - int(p[idx['IntendedVsync']])) / 1e6)
    worst.append(top)
print(f"{name}: {late} of {n} late ({100 * late / max(n, 1):.1f}%), slowest frame per gesture ms: {' '.join(f'{w:.0f}' for w in worst)}")
