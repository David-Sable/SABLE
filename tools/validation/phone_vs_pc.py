"""Plays real songs on the phone and checks each gain against the PC engine (device == host, end to end).
(Working folder: %TEMP%/aud holds decide.jsonl, tool2.exe from plan_tool.cpp; see docs/TESTING.md.)
"""
import subprocess,json,os,re,random,time,sys
T=os.environ['TEMP']+'/aud/'
def adb(*a,**k): return subprocess.run(['adb']+list(a),capture_output=True,text=True,encoding='utf-8',errors='replace',**k).stdout
rows=[json.loads(l) for l in open(T+'decide.jsonl',encoding='utf-8')]
for r in rows:
    for k in('shape','sections'): r.pop(k,None)
raw=adb('shell',"content query --uri content://media/external/audio/media --projection _display_name:title:duration:album:relative_path --where \"is_music=1 AND relative_path NOT LIKE 'Music/SableTest2/%'\"")
phone=[]
for l in raw.split('\n'):
    m=re.match(r'Row: \d+ _display_name=(.*?), title=(.*?), duration=(\d+), album=(.*?), relative_path=(.*)$',l.strip())
    if m: phone.append(dict(zip(['name','title','dur','album','path'],m.groups())))
def key(t,d): return (t.strip().lower(),round(d/1000))
idx={}
for r in rows: idx.setdefault(key(r['title'],int(1000*r['frames']/r['sampleRateHz'])),[]).append(r)
pairs=[]
for p in phone:
    c=idx.get(key(p['title'],int(p['dur'])),[])
    if len(c)==1: pairs.append((p,c[0]))
print('phone songs',len(phone),'matched to corpus decisions',len(pairs))
random.seed(11); skip=('Steady Mobbin','A Bird In The Hand')
pick=[x for x in random.sample(pairs,min(80,len(pairs))) if not any(s in x[0]['name'] for s in skip)][:int(sys.argv[1]) if len(sys.argv)>1 else 60]
def plan(a):
    inp={'targetLufs':-14,'intimacyLu':1.0,'ceilingDbtp':-1.0,'outputCalDb':0,'albumMode':'track','nowDays':20000.5,'albums':{},'items':[{'analysis':a,'manual':True,'albumIntent':False}]}
    p=subprocess.run([T+'tool2.exe','plan'],input=json.dumps(inp),capture_output=True,text=True); return json.loads(p.stdout)['items'][0]
bad=[];ok=0;skipped=0
adb('shell','input keyevent KEYCODE_MEDIA_PAUSE')
for p,r in pick:
    t0=adb('shell','date "+%m-%d %H:%M:%S.000"').strip()
    adb('shell','am start -n app.sable/.ui.MainActivity --es cmd playsong --es match \'%s\''%p['name'].replace("'","'\''"))
    time.sleep(2.3)
    log=adb('logcat','-d','-T',t0,'-s','SablePlay:I')
    m=[l for l in log.split('\n') if ' ITEM ' in l and 'manual=true' in l]
    if not m: bad.append((p['name'],'no ITEM line')); continue
    l=m[-1]
    g=re.search(r'gain (-?[\d.]+) dB manual=true (\w+) at ([^l]*?) learned ([+-][\d.]+) (.*)$',l)
    if not g: bad.append((p['name'],'unparsed: '+l[-100:])); continue
    gain,src,place,learned,rules=float(g.group(1)),g.group(2),g.group(3).strip(),float(g.group(4)),g.group(5)
    if 'FLOW' in rules or 'ALBUM' in rules or src!='full': skipped+=1; continue
    e=plan({k:v for k,v in r.items() if k not in('path','artist','title','album')})
    exp=e['manualGainDb']+learned
    if abs(exp-gain)>0.011: bad.append((p['name'],'phone %+.2f vs PC %+.2f (learned %+.2f) rules %s'%(gain,exp,learned,rules)))
    else: ok+=1
adb('shell','input keyevent KEYCODE_MEDIA_PAUSE')
print('compared',ok+len(bad),'| identical to the PC within 0.01 dB:',ok,'| skipped (flow run/not full):',skipped,'| different:',len(bad))
for n,w in bad[:10]: print('  DIFF',n.encode('ascii','replace').decode()[:50],w[:140].encode('ascii','replace').decode())
