"""Property checks on real queues (gain <= cap, deterministic, one gain per album run, quiet <= standard, stricter ceiling never raises gain, calibration moves by <= 1 dB per dB).
(Working folder: %TEMP%/aud holds decide.jsonl, tool2.exe from plan_tool.cpp; see docs/TESTING.md.)
"""
import json,os,random,subprocess,sys,collections
T=os.environ['TEMP']+'/aud/'
rows=[json.loads(l) for l in open(T+'decide.jsonl',encoding='utf-8')]
for r in rows:
    for k in ('shape','sections'): r.pop(k,None)
random.seed(7)
A=lambda r:{k:v for k,v in r.items() if k not in('path','artist','title','album')}
def plan(inp):
    p=subprocess.run([T+'tool2.exe','plan'],input=json.dumps(inp),capture_output=True,text=True)
    return json.loads(p.stdout)
by=collections.defaultdict(list)
for r in rows: by[(r['artist'],r['album'])].append(r)
albums=list(by.items())
viol=collections.Counter();ex={}
def chk(name,ok,info=None):
    if not ok:
        viol[name]+=1
        ex.setdefault(name,info)
N=300
for it in range(N):
    # a mixed queue: some from an album in order, some random songs
    (ar,al),tr=random.choice(albums)
    key=al+'|'+ar
    seq=[]
    if random.random()<0.6:
        n=min(len(tr),random.randint(2,8)); s=random.randint(0,max(0,len(tr)-n))
        seq+= [(r,key,True,s+i+1) for i,r in enumerate(tr[s:s+n])]
    for _ in range(random.randint(0,4)):
        r=random.choice(rows); seq.append((r,None,False,0))
    if not seq: continue
    def mk(T_,ceil,cal,mode='auto',manual0=True):
        items=[]
        for i,(r,k,ai,tn) in enumerate(seq):
            o={'analysis':A(r),'albumIntent':ai,'manual':manual0 if i==0 else False,'disc':1,'trackNo':tn}
            if k: o['album']=k
            items.append(o)
        return {'targetLufs':T_,'intimacyLu':1.0,'ceilingDbtp':ceil,'outputCalDb':cal,'albumMode':mode,'nowDays':20000.5,
                'albums':{key:{'complete':True,'tracks':[A(x) for x in tr]}},'items':items}
    a=plan(mk(-14,-1.0,0)); a2=plan(mk(-14,-1.0,0)); q=plan(mk(-19,-1.0,0)); c=plan(mk(-14,-1.5,0)); u=plan(mk(-14,-1.0,1))
    chk('deterministic',json.dumps(a)==json.dumps(a2),None)
    for i,(x,y,z,w) in enumerate(zip(a['items'],q['items'],c['items'],u['items'])):
        if 'manualGainDb' not in w: print('MISSING',json.dumps(w)[:200]); print(json.dumps(mk(-14,-1.0,1))[:0]); sys.exit(1)
        cap=x.get('capDb')
        for nm,v in (('gainDb',x['gainDb']),('manualGainDb',x['manualGainDb'])):
            if cap is not None: chk('gain<=cap (%s)'%nm,v<=cap+1e-9,(seq[i][0]['title'],v,cap))
        chk('quiet<=standard',y['manualGainDb']<=x['manualGainDb']+1e-9,(seq[i][0]['title'],y['manualGainDb'],x['manualGainDb']))
        chk('quiet-standard>=-5',y['manualGainDb']>=x['manualGainDb']-5-1e-9,(seq[i][0]['title'],y['manualGainDb'],x['manualGainDb']))
        chk('stricter ceiling never raises',z['manualGainDb']<=x['manualGainDb']+1e-9,(seq[i][0]['title'],z['manualGainDb'],x['manualGainDb']))
        chk('cal +1 raises by <=1',w['manualGainDb']-x['manualGainDb']<=1+1e-9 and w['manualGainDb']>=x['manualGainDb']-1e-9,(seq[i][0]['title'],w['manualGainDb'],x['manualGainDb']))
        chk('learned in +-2',abs(x.get('learnedDb',0))<=2+1e-9,None)
        chk('finite gains',all(abs(v)<100 for v in (x['gainDb'],x['manualGainDb'])),None)
    runs=[(i,x) for i,x in enumerate(a['items']) if x['albumRun']]
    if len(runs)>1:
        g=set(round(x['manualGainDb'],9) for _,x in runs)
        chk('album run: one gain',len(g)==1,[(seq[i][0]['title'][:18],round(x['manualGainDb'],2)) for i,x in runs])
print('queues:',N)
print('violations:',dict(viol) if viol else 'none')
for k,v in ex.items(): print('  e.g.',k,v)
