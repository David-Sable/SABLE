"""Natural song-to-song transitions on the phone against the PC planner (seam smoothing).
(Working folder: %TEMP%/aud holds decide.jsonl, tool2.exe from plan_tool.cpp; see docs/TESTING.md.)
"""
import subprocess,json,os,re,random,time,sys
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
def adb(*a): return subprocess.run(['adb']+list(a),capture_output=True,text=True,encoding='utf-8',errors='replace').stdout
def devnow(): return adb('shell','date "+%m-%d %H:%M:%S.000"').strip()
def setvol(n): adb('shell','cmd media_session volume --stream 3 --set %d'%n)
def key(k): adb('shell','input keyevent '+k)
T=os.environ['TEMP']+'/aud/'
rows=[json.loads(l) for l in open(T+'decide.jsonl',encoding='utf-8')]
for r in rows:
    for k in('shape','sections'): r.pop(k,None)
raw=adb('shell',"content query --uri content://media/external/audio/media --projection _id:_display_name:title:duration --where \"is_music=1 AND relative_path NOT LIKE 'Music/SableTest2/%'\"")
ph={}
for l in raw.split('\n'):
    m=re.match(r'Row: \d+ _id=(\d+), _display_name=(.*?), title=(.*?), duration=(\d+)$',l.strip())
    if m: ph[int(m.group(1))]=dict(name=m.group(2),title=m.group(3),dur=int(m.group(4)))
idx={}
for r in rows: idx.setdefault((r['title'].strip().lower(),round(1000*r['frames']/r['sampleRateHz']/1000)),[]).append(r)
def corpus(mid):
    p=ph.get(mid)
    if not p: return None
    c=idx.get((p['title'].strip().lower(),round(p['dur']/1000)),[])
    return c[0] if len(c)==1 else None
A=lambda r:{k:v for k,v in r.items() if k not in('path','artist','title','album')}
def plan2(a,b):
    inp={'targetLufs':-14,'intimacyLu':1.0,'ceilingDbtp':-1.0,'outputCalDb':0,'albumMode':'track','nowDays':20000.5,'albums':{},
         'items':[{'analysis':A(a),'manual':True,'albumIntent':False},{'analysis':A(b),'manual':False,'albumIntent':False}]}
    p=subprocess.run([T+'tool2.exe','plan'],input=json.dumps(inp),capture_output=True,text=True); return json.loads(p.stdout)['items']
names=sorted([(p['title'].lower(),mid) for mid,p in ph.items()])
random.seed(21)
cand=random.sample(range(len(names)-1),40)
setvol(0); key('KEYCODE_MEDIA_PAUSE')
ok=0;bad=[];nseam=0;tested=0
for i in cand:
    if tested>=int(sys.argv[1]): break
    t,mid=names[i]; p=ph[mid]
    if any(s in p['name'] for s in ('Steady Mobbin','A Bird In The Hand')): continue
    t0=devnow()
    adb('shell',"am start -n app.sable/.ui.MainActivity --es cmd playsong --es match \"%s\""%p['name'].replace('"','\\"'))
    time.sleep(2.5)
    adb('shell','am start -n app.sable/.ui.MainActivity --es cmd open --es screen nowplaying'); time.sleep(0.8)
    adb('shell','input tap %d 1640'%int(68+0.985*944)); time.sleep(7)
    log=adb('logcat','-d','-T',t0,'-s','SablePlay:I')
    its=[l for l in log.split('\n') if re.search(r' ITEM \S+ gain',l)]
    nat=[l for l in its if 'manual=false' in l]
    if not its or not nat: bad.append((p['name'],'no natural transition seen')); continue
    first=its[0]; second=nat[-1]
    g1=re.search(r'ITEM (\d+):\S+ gain (-?[\d.]+) dB manual=(\w+) (\w+) .*?learned ([+-][\d.]+) (.*)$',first)
    g2=re.search(r'ITEM (\d+):\S+ gain (-?[\d.]+) dB manual=(\w+) (\w+) .*?learned ([+-][\d.]+) (.*)$',second)
    a=corpus(int(g1.group(1))); b=corpus(int(g2.group(1)))
    if not a or not b or 'FLOW' in g2.group(6) or 'ALBUM' in g2.group(6) or g2.group(4)!='full': continue
    tested+=1
    e=plan2(a,b)[1]['gainDb']+float(g2.group(5))
    seam='SEAM' in g2.group(6)
    nseam+=seam
    if abs(e-float(g2.group(2)))>0.011: bad.append((p['name'],'phone %+.2f vs PC %+.2f %s'%(float(g2.group(2)),e,g2.group(6)[:50])))
    else: ok+=1
    key('KEYCODE_MEDIA_PAUSE')
setvol(2); key('KEYCODE_MEDIA_PAUSE')
print('natural transitions compared:',ok+len([b for b in bad if 'phone' in b[1]]),'| identical to the PC:',ok,'| with a SEAM adjustment:',nseam,'| problems:',len(bad))
for n,w in bad[:8]: print('  ',n.encode('ascii','replace').decode()[:44],w.encode('ascii','replace').decode()[:120])
