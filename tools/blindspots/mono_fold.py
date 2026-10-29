"""Per song: how much quieter it gets when a mono speaker folds left and right together, relative to a centred (mono)
song. loss = (I_stereo - I_mono) - 3.01 dB: 0 for mono content, 3 for uncorrelated sides, more when sides cancel."""
import os,re,subprocess,json,sys
from concurrent.futures import ThreadPoolExecutor
root=os.path.expanduser('~/dev/sable-corpus')
files=[os.path.join(dp,f) for d in ['drive','phone-extra'] for dp,_,fs in os.walk(os.path.join(root,d)) for f in fs if f.lower().endswith(('.flac','.mp3','.m4a','.wav'))]
def I(p,af):
    r=subprocess.run(['ffmpeg','-nostats','-i',p,'-af',af+'ebur128','-f','null','-'],capture_output=True,text=True,encoding='utf-8',errors='replace')
    m=re.findall(r'I:\s+(-?[\d.]+) LUFS',r.stderr); return float(m[-1]) if m else None
def run(p):
    s=I(p,'aformat=channel_layouts=stereo,'); m=I(p,'pan=mono|c0=0.5*c0+0.5*c1,')
    return {'path':os.path.relpath(p,root).replace(os.sep,'/'),'I':s,'Imono':m,'loss':None if s is None or m is None else round(s-m-3.0103,2)}
done=set()
op=os.path.expanduser('~/dev/sable-blindspots/mono.jsonl')
if os.path.exists(op): done={json.loads(l)['path'] for l in open(op,encoding='utf-8')}
files=[p for p in files if os.path.relpath(p,root).replace(os.sep,'/') not in done]
with open(op,'a',encoding='utf-8') as f, ThreadPoolExecutor(3) as ex:
    for d in ex.map(run,files): f.write(json.dumps(d,ensure_ascii=False)+'\n'); f.flush()
print('done',len(files))
