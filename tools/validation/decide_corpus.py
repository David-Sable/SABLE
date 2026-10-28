"""Runs sable_cli --decide over every FLAC in ~/dev/sable-corpus/drive (4 at a time) and writes %TEMP%/aud/decide.jsonl: the engine's figure for each song, used by every other script here.
(Working folder: %TEMP%/aud holds decide.jsonl, tool2.exe from plan_tool.cpp; see docs/TESTING.md.)
"""
import json,os,subprocess,sys,tempfile
from concurrent.futures import ThreadPoolExecutor
root=os.path.expanduser('~/dev/sable-corpus')
cli='C:/Users/Dana/Projects/SABLE/engine/build-host/sable_cli.exe'
man=json.load(open(root+'/manifest.json',encoding='utf-8'))
files=[f for f in (man['files'] if 'files' in man else list(man.values())[0]) if f['path'].startswith('drive/')]
out=os.environ['TEMP']+'/aud/decide.jsonl'
tmp=tempfile.mkdtemp(prefix='aud-')
def run(a):
    i,e=a
    w=f'{tmp}/{i}.wav'
    try:
        subprocess.run(['ffmpeg','-v','error','-y','-i',root+'/'+e['path'],'-map','0:a:0','-c:a','pcm_f32le',w],check=True)
        d=json.loads(subprocess.run([cli,'--decide',w],capture_output=True,text=True,check=True).stdout)
        d['path']=e['path'];d['artist']=e['artist'];d['title']=e['title'];d['album']=e['album']
        return d
    except Exception as ex:
        return {'path':e['path'],'error':str(ex)[:100]}
    finally:
        if os.path.exists(w): os.remove(w)
with open(out,'w',encoding='utf-8') as f, ThreadPoolExecutor(4) as ex:
    for d in ex.map(run,enumerate(files)):
        f.write(json.dumps(d,ensure_ascii=False)+'\n'); f.flush()
print('done')
