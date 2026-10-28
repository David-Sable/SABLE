"""Generates known-loudness test audio with ffmpeg (quiet/hot masters, quiet intro, flowing tracks, and odd files: surround, 192 kHz, silence, DC, clipped, truncated, garbage, 90 min). Usage: gen_test_audio.py <folder>; never commit the output.
(Working folder: %TEMP%/aud holds decide.jsonl, tool2.exe from plan_tool.cpp; see docs/TESTING.md.)
"""
import subprocess,re,os,sys,random
SP=sys.argv[1]; G=SP+'/gen'
for d in ('album','odd'): os.makedirs(G+'/'+d,exist_ok=True)
def ff(*a): return subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y']+list(a),capture_output=True,text=True)
def lufs(path):
    r=subprocess.run(['ffmpeg','-nostats','-i',path,'-af','ebur128=peak=true','-f','null','-'],capture_output=True,text=True)
    m=re.findall(r'I:\s+(-?[\d.]+) LUFS',r.stderr); return float(m[-1]) if m else None
def pink(path,dur,sr=44100,ch=2,amp=0.2,seed=1):
    lay='mono' if ch==1 else ('5.1' if ch==6 else 'stereo')
    if ch==2: src='anoisesrc=d=%s:c=pink:a=%s:r=%d:seed=%d'%(dur,amp,sr,seed)
    else:
        src=';'.join('anoisesrc=d=%s:c=pink:a=%s:r=%d:seed=%d'%(dur,amp,sr,seed+i) for i in range(ch))
    return src
def make(name,dur,target,sr=44100,ch=2,bits=16,codec='flac',tags=None,clip=False,pre='',post=''):
    base=G+'/_b.wav'
    if ch==2: ff('-f','lavfi','-i','anoisesrc=d=%s:c=pink:a=0.2:r=%d:seed=%d'%(dur,sr,random.randint(1,999)),'-ac','2',base)
    elif ch==1: ff('-f','lavfi','-i','anoisesrc=d=%s:c=pink:a=0.2:r=%d:seed=%d'%(dur,sr,random.randint(1,999)),'-ac','1',base)
    else:
        ins=[];
        for i in range(ch): ins+=['-f','lavfi','-i','anoisesrc=d=%s:c=pink:a=0.2:r=%d:seed=%d'%(dur,sr,random.randint(1,999))]
        ff(*ins,'-filter_complex',''.join('[%d:a]'%i for i in range(ch))+'amerge=inputs=%d,channelmap=channel_layout=5.1'%ch,base)
    l=lufs(base); g=target-l
    flt='volume=%fdB'%g+(',alimiter=limit=0.999:level=disabled' if clip else '')+post
    out=name; args=['-i',base,'-af',flt]
    if codec=='flac': args+=['-c:a','flac','-sample_fmt','s32' if bits==24 else 's16']
    elif codec=='wav': args+=['-c:a','pcm_f32le']
    for k,v in (tags or {}).items(): args+=['-metadata','%s=%s'%(k,v)]
    ff(*args,out); return out
def tagd(title,track,album='SableTestAlbum',artist='Sable Test'): return {'title':title,'track':str(track),'album':album,'artist':artist,'album_artist':artist}
# --- album: 5 tracks
make(G+'/album/01 Quiet master.flac',60,-23,tags=tagd('Quiet master',1))
make(G+'/album/02 Hot master.flac',60,-8,tags=tagd('Hot master',2),clip=True)
# quiet intro: 20 s at -30 then 40 s at -14
make(G+'/_i1.flac',20,-30); make(G+'/_i2.flac',40,-14)
ff('-i',G+'/_i1.flac','-i',G+'/_i2.flac','-filter_complex','[0:a][1:a]concat=n=2:v=0:a=1','-c:a','flac','-metadata','title=Quiet intro','-metadata','track=3','-metadata','album=SableTestAlbum','-metadata','artist=Sable Test','-metadata','album_artist=Sable Test',G+'/album/03 Quiet intro.flac')
make(G+'/album/04 Flow A.flac',45,-14,tags=tagd('Flow A',4)); make(G+'/album/05 Flow B.flac',45,-14,tags=tagd('Flow B',5))
# --- odd files
O=G+'/odd/'
make(O+'odd_mono_22k.flac',30,-16,sr=22050,ch=1,tags=tagd('Odd mono 22k',1,'SableOdd'))
make(O+'odd_hires_192k.flac',20,-16,sr=192000,bits=24,tags=tagd('Odd hires 192k',2,'SableOdd'))
make(O+'odd_surround_51.flac',20,-16,sr=48000,ch=6,tags=tagd('Odd surround 5.1',3,'SableOdd'))
make(O+'odd_float32.wav',20,-16,codec='wav',tags=tagd('Odd float32',4,'SableOdd'))
make(O+'odd_tiny_1s.flac',1,-16,tags=tagd('Odd tiny 1s',5,'SableOdd'))
make(O+'odd_tiny_half.flac',0.5,-16,tags=tagd('Odd tiny half',6,'SableOdd'))
make(O+'odd_clipped_loud.flac',30,-4,tags=tagd('Odd clipped loud',7,'SableOdd'),clip=True,pre='')
ff('-f','lavfi','-i','anullsrc=r=44100:cl=stereo','-t','30','-c:a','flac','-metadata','title=Odd silence','-metadata','album=SableOdd','-metadata','artist=Sable Test','-metadata','track=8',O+'odd_silence.flac')
ff('-f','lavfi','-i','aevalsrc=0.5:s=44100:d=20:c=stereo','-c:a','flac','-metadata','title=Odd DC offset','-metadata','album=SableOdd','-metadata','artist=Sable Test','-metadata','track=9',O+'odd_dc.flac')
ff('-f','lavfi','-i','aevalsrc=if(lt(mod(t*1000\,1)\,0.5)\,1\,-1):s=44100:d=20:c=stereo','-c:a','flac','-metadata','title=Odd square fullscale','-metadata','album=SableOdd','-metadata','artist=Sable Test','-metadata','track=10',O+'odd_square.flac')
# truncated + garbage
make(O+'_full.flac',60,-16,tags=tagd('Odd truncated',11,'SableOdd'))
b=open(O+'_full.flac','rb').read(); open(O+'odd_truncated.flac','wb').write(b[:int(len(b)*0.4)]); os.remove(O+'_full.flac')
open(O+'odd_garbage.flac','wb').write(os.urandom(50000)); open(O+'odd_zero.flac','wb').close(); open(O+'odd_text.mp3','w').write('this is not audio\n'*50)
# long: 90 min mono 8 kHz
make(O+'odd_long_90min_8k.flac',5400,-16,sr=8000,ch=1,tags=tagd('Odd long 90 min',12,'SableOdd'))
for f in ('_b.wav','_i1.flac','_i2.flac'):
    p=G+'/'+f
    if os.path.exists(p): os.remove(p)
print('ok')
