"""Joins every song lesson (per place) with the engine's reading of that song and its mono fold-down loss, and shows
which features lean with the direction of the lesson."""
import json,re,os
k=lambda s: re.sub(r'[^a-z0-9]','',os.path.basename(s).lower().replace('.flac',''))
A={k(d['path']):d for d in map(json.loads,open('all.jsonl',encoding='utf-8')) if 'error' not in d}
M={k(d['path']):d for d in map(json.loads,open('mono.jsonl',encoding='utf-8'))}
rows=[]
for l in open('learned_songs.txt',encoding='utf-8'):
    m=re.match(r'(.*?) · song (.*): ([+-][\d.]+) dB',l.strip())
    if not m: continue
    place,name,db=m.group(1),m.group(2),float(m.group(3)); kk=k(name)
    a=A.get(kk); mo=M.get(kk)
    if not a: print('no analysis for',name); continue
    rows.append((place,db,name[-38:],a,mo))
print(f"{'place':8} {'lesson':>6} {'song':38} {'gain':>6} {'L_I':>6} {'PLE-L':>6} {'tilt':>6} {'LRA':>5} {'PLR':>5} {'mono':>5} char")
for place,db,name,a,mo in sorted(rows,key=lambda r:(r[0],r[1])):
    print(f"{place[3:11] if place.startswith('bt:') else place:8} {db:+6.2f} {name:38} {a['standard']['gainDb']:+6.1f} {a['integratedLufs']:6.1f} {a['anchorPle']-a['anchorLufs']:+6.1f} {a['tilt']:6.2f} {a['lraLu']:5.1f} {a['plrDb']:5.1f} {(mo or {}).get('loss') if mo else 'na':>5} {a['character'][:12]}")
import statistics as st
for place in sorted(set(r[0] for r in rows)):
    R=[r for r in rows if r[0]==place]
    if len(R)<6: continue
    up=[r for r in R if r[1]>0]; dn=[r for r in R if r[1]<0]
    print('\n',place,'up',len(up),'down',len(dn))
    for name,f in [('gain',lambda a,m:a['standard']['gainDb']),('PLE-L',lambda a,m:a['anchorPle']-a['anchorLufs']),('tilt',lambda a,m:a['tilt']),('LRA',lambda a,m:a['lraLu']),('PLR',lambda a,m:a['plrDb']),('mono',lambda a,m:(m or {}).get('loss')),('L_I',lambda a,m:a['integratedLufs']),('trans/s',lambda a,m:a['transientsPerSec'])]:
        u=[f(r[3],r[4]) for r in up if f(r[3],r[4]) is not None]; d=[f(r[3],r[4]) for r in dn if f(r[3],r[4]) is not None]
        if u and d: print(f'  {name:8} up mean {st.mean(u):+6.2f}  down mean {st.mean(d):+6.2f}  diff {st.mean(u)-st.mean(d):+6.2f}  (sd all {st.pstdev(u+d):.2f})')
