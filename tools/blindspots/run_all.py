"""Runs sable_cli --decide over every audio file under the corpus folders given (default drive, phone-extra, free) and
writes ~/dev/sable-blindspots/all.jsonl: figures, sections and anchor per file (big per-time series dropped)."""
import json,os,subprocess,sys,tempfile
from concurrent.futures import ThreadPoolExecutor
root=os.path.expanduser('~/dev/sable-corpus')
cli='C:/Users/Dana/Projects/SABLE/engine/build-host/sable_cli.exe'
dirs=sys.argv[2:] or ['drive','phone-extra','free']
out=os.path.expanduser(sys.argv[1] if len(sys.argv)>1 else '~/dev/sable-blindspots/all.jsonl')
ext=('.flac','.mp3','.wav','.m4a','.ogg','.opus','.aac','.aiff','.alac','.wma')
files=[os.path.join(dp,f) for d in dirs for dp,_,fs in os.walk(os.path.join(root,d)) for f in sorted(fs) if f.lower().endswith(ext)]
tmp=tempfile.mkdtemp(prefix='bs-')
DROP={'shape','shapeTilt','anchorBlockPower'}
def run(a):
    i,p=a
    w=f'{tmp}/{i}.wav'
    try:
        subprocess.run(['ffmpeg','-v','error','-y','-i',p,'-map','0:a:0','-c:a','pcm_f32le',w],check=True)
        d=json.loads(subprocess.run([cli,'--decide',w],capture_output=True,text=True,check=True).stdout)
        d={k:v for k,v in d.items() if k not in DROP}
        d['path']=os.path.relpath(p,root).replace(os.sep,'/')
        return d
    except Exception as ex:
        return {'path':p,'error':str(ex)[:200]}
    finally:
        if os.path.exists(w): os.remove(w)
with open(out,'w',encoding='utf-8') as f, ThreadPoolExecutor(4) as ex:
    for d in ex.map(run,enumerate(files)):
        f.write(json.dumps(d,ensure_ascii=False)+'\n'); f.flush()
print('done',len(files))
