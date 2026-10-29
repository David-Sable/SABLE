"""Target sweep (MATH §16.1, §16.4, §17.2, §24.4): for each candidate target, how many songs and albums Sable can
bring all the way to it before the true-peak cap stops it, and by how much the rest fall short.
Reads the --decide JSONL files given (default %TEMP%/aud/decide.jsonl from tools/validation/decide_corpus.py). Song gain is linear in the target, so one
--decide run per song is enough: intent(T) = standard.intentDb + (T + 14), cap(C) = standard.capDb + (C + 1) − H.
Album loudness here is the duration-weighted power mean of the members' L_I (an approximation of §4.4's pooled
blocks, close enough to count caps); the album cap is the ceiling minus the loudest member's true peak.
"""
import collections,json,math,os,sys
srcs=sys.argv[1:] or [os.environ['TEMP']+'/aud/decide.jsonl']
rows=[json.loads(l) for s in srcs for l in open(s,encoding='utf-8')]
rows=[r for r in rows if 'error' not in r and r.get('standard') and r['standard'].get('intentDb') is not None]
H_CONTOUR=2.6  # §24.3 design at calibration 0: low shelf +2.0, high shelf +0.6
def pct(xs,p):
    xs=sorted(xs); return xs[min(len(xs)-1,int(p/100*len(xs)))] if xs else float('nan')
def sweep(name,targets,ceil,h):
    print(f'\n{name}: ceiling {ceil} dBTP, contour headroom {h} dB, {len(rows)} songs')
    print(' target | songs capped | short by >1 dB | median / worst shortfall | songs turned UP | albums capped (>1 dB)')
    albums=collections.defaultdict(list)
    for r in rows: albums[os.path.dirname(r['path'])].append(r)
    albums={k:v for k,v in albums.items() if len(v)>=3}
    for T in targets:
        short=[];up=0
        for r in rows:
            intent=r['standard']['intentDb']+(T+14); cap=r['standard']['capDb']+(ceil+1)-h
            if intent>0.05: up+=1
            if cap<intent: short.append(intent-cap)
        ac=0;ac1=0
        for v in albums.values():
            p=sum(r['frames']/r['sampleRateHz']*10**(r['integratedLufs']/10) for r in v)/sum(r['frames']/r['sampleRateHz'] for r in v)
            g=T-10*math.log10(p); cap=ceil-max(r['truePeakDbtp'] for r in v)-h
            if cap<g: ac+=1; ac1+=(g-cap)>1
        n=len(rows)
        print(f' {T:6.0f} | {len(short):3d} ({100*len(short)/n:4.1f}%) | {sum(s>1 for s in short):3d} ({100*sum(s>1 for s in short)/n:4.1f}%) |'
              f' {pct(short,50):4.1f} / {max(short,default=0):4.1f} dB | {up:3d} ({100*up/n:4.1f}%) | {ac}/{len(albums)} ({ac1})')
L=[r['integratedLufs'] for r in rows]; P=[r['truePeakDbtp'] for r in rows]; R=[r['truePeakDbtp']-r['integratedLufs'] for r in rows]
print(f'library: L_I median {pct(L,50):.1f} LUFS (5-95% {pct(L,5):.1f}..{pct(L,95):.1f}); true peak median {pct(P,50):.1f} dBTP;'
      f' PLR median {pct(R,50):.1f} dB (5-95% {pct(R,5):.1f}..{pct(R,95):.1f})')
sweep('Normal, phone speaker',[-10,-11,-12,-13,-14,-15,-16,-17,-18],-1.0,0)
sweep('Normal, Bluetooth',[-10,-11,-12,-13,-14,-15,-16,-17,-18],-1.5,0)
sweep('Quiet, phone speaker, contour on',[-16,-17,-18,-19,-20,-21,-22,-24],-1.0,H_CONTOUR)
sweep('Quiet, Bluetooth, contour on',[-16,-17,-18,-19,-20,-21,-22,-24],-1.5,H_CONTOUR)
