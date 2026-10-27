import sys
lines=open(sys.argv[1],encoding='utf-8',errors='ignore').read().splitlines()
i=[k for k,l in enumerate(lines) if l.startswith('Flags,')][0]
hdr=lines[i].split(','); idx={h:j for j,h in enumerate(hdr) if h}
n=late=0
for l in lines[i+1:]:
    if not l.strip() or l.startswith('---'): break
    p=l.split(',')
    if p[0]!='0': continue
    n+=1; late+= int(p[idx['FrameCompleted']])>int(p[idx['FrameDeadline']])
print(f"{sys.argv[2]}: {late} of {n} frames reached the screen late ({100*late/max(n,1):.1f}%)")
