"""Scripted volume-key learning tests on the phone: play a song, press keys at exact times, read the LEARN lines. See the scenarios in docs/TESTING.md.
(Working folder: %TEMP%/aud holds decide.jsonl, tool2.exe from plan_tool.cpp; see docs/TESTING.md.)
"""
import subprocess,time,re,sys,json
def adb(*a):
    return subprocess.run(['adb']+list(a),capture_output=True,text=True,encoding='utf-8',errors='replace').stdout
def devnow(): return adb('shell','date "+%m-%d %H:%M:%S.000"').strip()
def vol(): 
    m=re.search(r'volume is (\d+)',adb('shell','cmd media_session volume --stream 3 --get')); return int(m.group(1)) if m else None
def setvol(n): adb('shell','cmd media_session volume --stream 3 --set %d'%n)
def play(name):
    adb('shell',"am start -n app.sable/.ui.MainActivity --es cmd playsong --es match \"%s\""%name.replace('"','\\"'))
def key(k): adb('shell','input keyevent '+k)
def run(title,song,vol0,steps,wait=26,mode=None):
    """steps: list of (t seconds after play, action). actions: DOWN UP PAUSE PLAY NEXT PREV SEEK:0.7 VOL:n"""
    key('KEYCODE_MEDIA_PAUSE'); time.sleep(0.8)
    setvol(vol0); time.sleep(0.6)
    t0=devnow(); st=time.time(); play(song)
    for t,a in steps:
        while time.time()-st<t: time.sleep(0.02)
        if a=='DOWN': key('KEYCODE_VOLUME_DOWN')
        elif a=='UP': key('KEYCODE_VOLUME_UP')
        elif a=='PAUSE': key('KEYCODE_MEDIA_PAUSE')
        elif a=='PLAY': key('KEYCODE_MEDIA_PLAY')
        elif a=='NEXT': key('KEYCODE_MEDIA_NEXT')
        elif a=='PREV': key('KEYCODE_MEDIA_PREVIOUS')
        elif a.startswith('VOL:'): setvol(int(a[4:]))
        elif a=='NP': adb('shell','am start -n app.sable/.ui.MainActivity --es cmd open --es screen nowplaying')
        elif a.startswith('SEEK:'):
            # Now Playing waveform: tap at the fraction of its width (screen 1080 px wide, waveform y ~ 1530)
            f=float(a[5:]); adb('shell','input tap %d 1640'%int(68+f*(1080-136)))
    while time.time()-st<wait: time.sleep(0.2)
    v1=vol()
    log=adb('logcat','-d','-T',t0,'-s','SablePlay:I')
    out=[]
    for l in log.split('\n'):
        if re.search(r'LEARN|volume index| ITEM ',l):
            l=re.sub(r'^([\d-]+ [\d:.]+) +\d+ +\d+ I SablePlay: ',r'\1 ',l)
            l=re.sub(r' ITEM [0-9:A-Z]+ ',' ITEM ',l)
            out.append(l.strip()[:230])
    print('--- %s | %s | vol %s -> %s'%(title,song,vol0,v1))
    for l in out: print('   ',l.encode('ascii','replace').decode())
    key('KEYCODE_MEDIA_PAUSE')
    return out
if __name__=='__main__':
    pass
