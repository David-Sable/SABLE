"""Fuzzes planQueueJson / learnUpdateJson with mutated, extreme and malformed inputs: no crash, valid JSON, finite numbers, learning inside its clamp. Usage: fuzz_planner.py <seed> <cases>.
(Working folder: %TEMP%/aud holds decide.jsonl, tool2.exe from plan_tool.cpp; see docs/TESTING.md.)
"""
import json,os,random,subprocess,math,copy,sys
T=os.environ['TEMP']+'/aud/'
rows=[json.loads(l) for l in open(T+'decide.jsonl',encoding='utf-8')]
for r in rows:
    for k in ('shape','sections'): r.pop(k,None)
random.seed(int(sys.argv[1]) if len(sys.argv)>1 else 1)
EXTREME=[0,-0.0,1e308,-1e308,1e-320,-1e-320,1e30,-1e30,3.4e38,-200,200,-1000,1000,None,True,False,"x","",[],{},[1,2],{"a":1},2**63,-2**63,1e400 if False else 1e308*10 if False else 9e307]
def run(mode,obj,raw=None):
    data=raw if raw is not None else json.dumps(obj)
    p=subprocess.run([T+'tool2.exe',mode],input=data,capture_output=True,text=True,timeout=30)
    return p.returncode,p.stdout
def finite(x):
    if isinstance(x,dict): return all(finite(v) for v in x.values())
    if isinstance(x,list): return all(finite(v) for v in x)
    if isinstance(x,float): return math.isfinite(x)
    return True
def mutate(o,depth=0):
    o=copy.deepcopy(o)
    def walk(x):
        if isinstance(x,dict):
            ks=list(x.keys())
            if ks and random.random()<0.35:
                k=random.choice(ks); r=random.random()
                if r<0.2: del x[k]
                elif r<0.8: x[k]=random.choice(EXTREME)
                else: x[k]=(x[k]*random.choice([-1,0,10,1e6]) if isinstance(x[k],(int,float)) and not isinstance(x[k],bool) else random.choice(EXTREME))
            for v in list(x.values()): walk(v)
        elif isinstance(x,list):
            if x and random.random()<0.2: x.pop(random.randrange(len(x)))
            for v in x: walk(v)
    walk(o); return o
def item(r,**kw):
    a={k:v for k,v in r.items() if k not in('path','artist','title','album')}
    d={'analysis':a,'manual':random.random()<0.5,'albumIntent':random.random()<0.5,'disc':1,'trackNo':random.randint(0,20)}
    d.update(kw); return d
bad=[];n=0
for it in range(int(sys.argv[2]) if len(sys.argv)>2 else 1500):
    pick=random.sample(rows,random.randint(0,4))
    base={'targetLufs':random.choice([-14,-19,-14.0,-30,0,-100]),'intimacyLu':random.choice([0,1.0,5,-3]),'ceilingDbtp':random.choice([-1.0,-1.5,-2.0,0,-20,5]),
          'outputCalDb':random.choice([0,3,-3,100,-100]),'albumMode':random.choice(['auto','track','album','bogus',None]),'contourHeadroomDb':random.choice([0,2,-2,50]),'nowDays':20000.5,
          'albums':{},'items':[item(r,**({'learn':{'trackDb':random.uniform(-9,9),'trackDays':random.uniform(19000,20001),'artistDb':random.uniform(-9,9),'artistDays':random.uniform(19000,20001),'artistTracks':random.randint(0,5),'trackTaught':random.random()<0.5,'artistTrackDbs':[random.uniform(-3,3) for _ in range(random.randint(0,4))]}} if random.random()<0.5 else {})) for r in pick]}
    if pick and random.random()<0.5:
        base['albums']={'k':{'complete':True,'tracks':[{k:v for k,v in r.items() if k not in('path','artist','title','album')} for r in pick]}}
        for x in base['items']: x['album']='k'
    case=mutate(base) if random.random()<0.9 else base
    try: rc,out=run('plan',case)
    except subprocess.TimeoutExpired: bad.append(('TIMEOUT',case)); continue
    n+=1
    if rc!=0: bad.append(('CRASH rc=%d'%rc,case)); continue
    try: j=json.loads(out)
    except Exception: bad.append(('NOT JSON: '+out[:80],case)); continue
    if not finite(j): bad.append(('NONFINITE',case)); continue
    if 'items' in j and base['items'] is not None:
        for x in j['items']:
            for k in('gainDb','manualGainDb'):
                if k in x and isinstance(x[k],(int,float)) and abs(x[k])>200: bad.append(('HUGE GAIN %s=%s'%(k,x[k]),case)); break
# learn fuzz
for it in range(1500):
    base={'deltaDb':random.uniform(-15,15),'trackDb':random.uniform(-3,3),'trackDays':random.uniform(19990,20001),'artistDb':random.uniform(-3,3),'artistDays':random.uniform(19990,20001),'nowDays':20001.0,
          'prevDeltaDb':random.uniform(-8,8),'prevDays':20000.99,'startDb':random.uniform(-60,0),'prevEndDb':random.uniform(-60,0),'heardLufs':random.uniform(-40,0),'anchorLufs':random.uniform(-30,0),'jumpDays':20000.99,'jumpEndDb':random.uniform(-60,0),'muted':random.random()<0.1}
    case=mutate(base)
    try: rc,out=run('learn',case)
    except subprocess.TimeoutExpired: bad.append(('LEARN TIMEOUT',case)); continue
    n+=1
    if rc!=0: bad.append(('LEARN CRASH rc=%d'%rc,case)); continue
    try: j=json.loads(out)
    except Exception: bad.append(('LEARN NOT JSON: '+out[:80],case)); continue
    if not finite(j): bad.append(('LEARN NONFINITE',case)); continue
    for k in('trackDb','artistDb'):
        v=j.get(k)
        if isinstance(v,(int,float)) and abs(v)>(4.0000001 if k=='trackDb' else 2.0000001): bad.append(('LEARN OUT OF CLAMP %s=%s'%(k,v),case))
# raw garbage
for raw in ['','{','}','[]','null','{"items":[]}','{"items":null}','{"items":[null]}','{"items":[{}]}','{"items":[[]]}','{"items":'+'['*200+']'*200+'}','{"items":[{"analysis":'+'{"a":'*300+'1'+'}'*300+'}]}','\x00','{"targetLufs":"a"}','{"items":[{"learn":null}]}','{"items":[{"learn":5}]}','{"albums":5,"items":[]}','{"albums":{"k":5},"items":[{"album":"k","analysis":{}}]}']:
    for mode in('plan','learn'):
        try: rc,out=run(mode,None,raw)
        except subprocess.TimeoutExpired: bad.append(('RAW TIMEOUT',raw[:40])); continue
        n+=1
        if rc!=0: bad.append(('RAW CRASH %s rc=%d'%(mode,rc),raw[:60]))
print('cases run:',n,'problems:',len(bad))
seen=set()
for k,c in bad:
    key=k.split('=')[0]
    if key in seen: continue
    seen.add(key); print(' ',k,'|',(json.dumps(c)[:300] if not isinstance(c,str) else c))
json.dump([(k,c) for k,c in bad[:20]],open(T+'fuzz_bad.json','w'))
