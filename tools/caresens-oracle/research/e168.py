from sc import *
from kt import kt
from e16 import clip
np.set_printoptions(linewidth=230,precision=6,suppress=True)
spec={'seed': 947847232, 'readings': 4320, 'start': 1, 'slope100': 3.683247397040891, 'vref': 1.4397914036217043, 'eapp': 0.042596325735175716, 'lo': 60, 'hi': 90, 'knot_min': 30, 'noise': 5, 'temp_mode': 'const', 'temp_lo': 3036, 'decay': 0.5, 'gap_permille': 0, 'gap_max': 3, 'jitter': 30, 'spike_permille': 0, 'clock_jump_at': -1, 'clock_jump': 2000, 'knot_max': 150, 'temp_hi': 3036}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
od=np.abs(k['one']-g['tran_inA_1min']).max(1)*(o['data_type']!=2); q=np.argmax(od>1e-9); print('first minute diff',q+1,seq[q])
x=g['tran_inA'][q]; lib=s['prev_outlier_removed_curr'][q][30:]
raw=np.append(s['prev_current'][q-1],x); cl=np.append(s['prev_new_i_sig'][q-1],lib); fl=list(s['outlier_max_index'][q-1])
for j in range(30):
    h=cl if sum(1 for f in fl[-6:] if f)>=4 else raw
    z=np.append(h[j:j+5],x[j]); md=np.median(z); mad=np.median(abs(z-md)); sg=1.486*mad
    if sg==0: sg=1.253314*np.mean(abs(z-z.mean()))
    exp_clip=abs(x[j]-md)>1.5*sg
    lc=abs(lib[j]-x[j])>1e-12
    if exp_clip!=lc: print('j',j,'lib clip',lc,'mine',exp_clip,'mad',mad,'|v-md|',abs(x[j]-md),'1.5sg',1.5*sg,'window',z-md,'useclean',h is cl)
    fl.append((1 if x[j]>md else -1) if lc else 0)
print('====')
pc=clip(s['prev_current'][q-1],s['prev_new_i_sig'][q-1],s['outlier_max_index'][q-1],x)[0]; print('clip(no floor) diff',np.abs(pc-lib).max())
from cur import loess,gm,mins,smk
y=np.append(s['prev_outlier_removed_curr'][q-1],lib); f=loess(y)
T10=s['time_10sec_arr'][q].astype(np.int64); print('span90',T10[89]-T10[0],'dt',t[q]-t[q-1],'ts0-prevlast',(t[q]-290)-(t[q-1]))
for nm,v in (('loess+smk',mins(smk(gm(f[60:]),s['prev_mov_median_curr'][q-1]))),('loess',mins(gm(f[60:]))),('none+smk',mins(smk(gm(lib),s['prev_mov_median_curr'][q-1]))),('none',mins(gm(lib)))): print(nm,np.abs(v-g['tran_inA_1min'][q]).max())
print('kt minute',k['one'][q]-g['tran_inA_1min'][q])
