import json,os,subprocess,sys,glob,tempfile
cli='C:/Users/Dana/Projects/SABLE/engine/build-host/sable_cli.exe'
src=sys.argv[1]; out=sys.argv[2]
tmp=tempfile.mkdtemp()
with open(out,'w',encoding='utf-8') as f:
    for p in sorted(glob.glob(src+'/*')):
        w=tmp+'/x.wav'; subprocess.run(['ffmpeg','-v','error','-y','-i',p,'-c:a','pcm_f32le',w],check=True)
        d=json.loads(subprocess.run([cli,'--decide',w],capture_output=True,text=True).stdout)
        d={k:v for k,v in d.items() if k not in ('shape','shapeTilt','anchorBlockPower')}; d['path']=os.path.basename(p)
        f.write(json.dumps(d)+'\n')
