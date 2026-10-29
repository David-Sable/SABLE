"""Builds blind-spot test files from real library songs (never committed): ~/dev/sable-blindspots/cases/*.flac.
Each case says what a listener would expect, so the engine's reading can be judged against it."""
import subprocess,os,glob
C=os.path.expanduser('~/dev/sable-corpus'); O=os.path.expanduser('~/dev/sable-blindspots/cases'); os.makedirs(O,exist_ok=True)
def ff(*a):
    r=subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y']+list(a),capture_output=True,text=True)
    if r.returncode: print('FAIL',a[-1],r.stderr[:300])
def g(pat): return glob.glob(C+'/'+pat)[0]
POP=g('drive/FLACS/Ariana Grande*/01 imagine.flac')          # dense pop
RAP=g('drive/FLACS/Nas - Illmatic/*It Ain*')                  # 90s rap
PIANO=g('free/OpenGoldbergVariations/*01 Aria.flac')          # solo piano, quiet master
SPEECH=g('free/stories_006_librivox/after_twenty*.mp3')       # speech
o=lambda n: O+'/'+n+'.flac'
F=['-c:a','flac','-sample_fmt','s32']
# 1 hidden track: a song, 4 min of silence, then a quiet piano piece
ff('-i',POP,'-i',PIANO,'-filter_complex','[0:a]aformat=sample_rates=44100:channel_layouts=stereo[a];[1:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=0:90[b];anullsrc=r=44100:cl=stereo,atrim=0:240[s];[a][s][b]concat=n=3:v=0:a=1',*F,o('01_hidden_track'))
# 2 live: crowd noise (modulated pink) 25 s between and around two songs, crowd as loud as the music
crowd='anoisesrc=c=pink:a=0.5:d=25:r=44100,highpass=f=300,lowpass=f=6000,volume=0.6,apulsator=hz=1.3:amount=0.6,aformat=channel_layouts=stereo'
ff('-i',RAP,'-i',POP,'-filter_complex',f'{crowd}[c1];{crowd}[c2];{crowd}[c3];[0:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=0:120[a];[1:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=0:120[b];[c1][a][c2][b][c3]concat=n=5:v=0:a=1',*F,o('02_live_crowd'))
# 3 spoken skit 40 s (speech, quiet) then the song
ff('-i',SPEECH,'-i',RAP,'-filter_complex','[0:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=0:40,volume=-6dB[a];[1:a]aformat=sample_rates=44100:channel_layouts=stereo[b];[a][b]concat=n=2:v=0:a=1',*F,o('03_skit_then_song'))
# 4 slow crescendo: the pop song rising 30 dB over its length (Bolero-like)
ff('-i',POP,'-af','volume=eval=frame:volume=\'pow(10,(-30+30*t/212)/20)\'',*F,o('04_crescendo'))
# 5 one huge spike: the piano piece with a single full-scale click at 60 s
ff('-i',PIANO,'-af','aeval=exprs=\'val(0)+if(between(t,60,60.0005),0.99,0)\'|\'val(1)+if(between(t,60,60.0005),0.99,0)\'',*F,o('05_single_spike'))
# 6 out of phase: right = -left (cancels to nothing on a mono speaker)
ff('-i',POP,'-af','pan=stereo|c0=c0|c1=-1*c0',*F,o('06_out_of_phase'))
# 7 left channel only
ff('-i',POP,'-af','pan=stereo|c0=c0|c1=0*c1',*F,o('07_left_only'))
# 8 big DC offset (+0.3) on the rap song at -6 dB
ff('-i',RAP,'-af','volume=-6dB,dcshift=0.3',*F,o('08_dc_offset'))
# 9 long ambient intro: 90 s of quiet pad (-30 dB lowpassed noise) then the song
ff('-i',POP,'-filter_complex','anoisesrc=c=brown:a=0.3:d=90:r=44100,lowpass=f=800,volume=-24dB,aformat=channel_layouts=stereo[p];[0:a]aformat=sample_rates=44100:channel_layouts=stereo[a];[p][a]concat=n=2:v=0:a=1',*F,o('09_long_ambient_intro'))
# 10 telephone band 300-3400 Hz
ff('-i',RAP,'-af','highpass=f=300,highpass=f=300,lowpass=f=3400,lowpass=f=3400',*F,o('10_telephone'))
# 11 sub-bass only (below 80 Hz) of the rap song, normalised up
ff('-i',RAP,'-af','lowpass=f=80,lowpass=f=80,lowpass=f=80,volume=12dB',*F,o('11_sub_bass_only'))
# 12 8 kHz mono voice-memo style
ff('-i',SPEECH,'-af','atrim=0:120,aresample=8000','-ac','1','-c:a','flac',o('12_voice_memo_8k'))
# 13 quiet song with a short very loud ending (8 s at +15 dB over the rest)
ff('-i',PIANO,'-filter_complex','[0:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=0:150[a];anoisesrc=c=pink:a=0.5:d=8:r=44100,aformat=channel_layouts=stereo[n];[a][n]concat=n=2:v=0:a=1',*F,o('13_quiet_then_loud_ending'))
# 14 two-part suite: 3 min loud, 3 min 14 dB quieter (Pyramids-style)
ff('-i',POP,'-i',POP,'-filter_complex','[0:a]atrim=0:180[a];[1:a]atrim=0:180,lowpass=f=2500,volume=-14dB[b];[a][b]concat=n=2:v=0:a=1',*F,o('14_two_part_suite'))
# 15 stutter: the song gated 0.6 s on / 0.6 s off (glitch edit)
ff('-i',POP,'-af','volume=eval=frame:volume=\'if(lt(mod(t,1.2),0.6),1,0)\'',*F,o('15_stutter_gaps'))
# 16 harsh noise wall (white noise, limited) 3 min
ff('-f','lavfi','-i','anoisesrc=c=white:a=0.9:d=180:r=44100','-af','aformat=channel_layouts=stereo,alimiter=limit=0.9',*F,o('16_noise_wall'))
# 17 drone: a single 55 Hz + 110 Hz sine pad for 4 min
ff('-f','lavfi','-i','aevalsrc=0.4*sin(2*PI*55*t)+0.2*sin(2*PI*110*t):s=44100:d=240','-ac','2',*F,o('17_low_drone'))
# 18 high sine drone 6 kHz (ear-piercing) quiet
ff('-f','lavfi','-i','aevalsrc=0.05*sin(2*PI*6000*t):s=44100:d=120','-ac','2',*F,o('18_high_tone'))
# 19 very quiet master (whole song -30 dB)
ff('-i',POP,'-af','volume=-30dB',*F,o('19_very_quiet_master'))
# 20 long DJ-mix style: 4 songs back to back, 15 min+
ff('-i',POP,'-i',RAP,'-i',POP,'-i',RAP,'-filter_complex','[0:a]aformat=sample_rates=44100:channel_layouts=stereo[a];[1:a]aformat=sample_rates=44100:channel_layouts=stereo[b];[2:a]aformat=sample_rates=44100:channel_layouts=stereo,volume=-8dB[c];[3:a]aformat=sample_rates=44100:channel_layouts=stereo[d];[a][b][c][d]concat=n=4:v=0:a=1',*F,o('20_long_mix'))
print('made',len(os.listdir(O)))
