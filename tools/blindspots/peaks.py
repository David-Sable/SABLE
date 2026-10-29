"""How isolated is a song's peak? Splits the song into 10 ms windows, takes each window's sample peak, and reports how
many windows sit within 1, 3 and 6 dB of the song's peak and over how many separate seconds."""
import sys,subprocess,numpy as np
def load(p):
    raw=subprocess.run(['ffmpeg','-v','error','-i',p,'-f','f32le','-ac','2','-ar','44100','-'],capture_output=True).stdout
    return np.frombuffer(raw,np.float32).reshape(-1,2)
for p in sys.argv[1:]:
    x=np.abs(load(p)).max(axis=1); n=len(x)//441; w=x[:n*441].reshape(n,441).max(axis=1)
    pk=w.max(); db=20*np.log10(np.maximum(w,1e-9)/pk)
    out=[]
    for t in (1,3,6):
        idx=np.where(db>-t)[0]; out.append(f'>-{t}dB: {len(idx)} win in {len(set(idx//100))} s')
    print(p.split('/')[-1][:40], 'peak %.2f dBFS'%(20*np.log10(pk)), '|', '; '.join(out))
