from sc import *
from kt import kt
from e16 import clip
np.set_printoptions(linewidth=230,precision=6,suppress=True)
spec={'seed': 769894003, 'readings': 4320, 'start': 1, 'slope100': 4.196787406260732, 'vref': 1.431392060662356, 'eapp': 0.10158600551933888, 'lo': 120, 'hi': 370, 'knot_min': 60, 'noise': 5, 'temp_mode': 'const', 'temp_lo': 3186, 'decay': 0.9, 'gap_permille': 10, 'gap_max': 3, 'jitter': 0, 'spike_permille': 0, 'clock_jump_at': 200, 'clock_jump': 2000, 'knot_max': 600, 'temp_hi': 3486}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
od=np.abs(k['one']-g['tran_inA_1min']).max(1)*(o['data_type']!=2); q=np.argmax(od>1e-9); print('first minute diff',q+1,seq[q])
x=g['tran_inA'][q]; lib=s['prev_outlier_removed_curr'][q][30:]
raw=np.append(s['prev_current'][q-1],x); cl=np.append(s['prev_new_i_sig'][q-1],lib); fl=list(s['outlier_max_index'][q-1])
for j in range(30):
    h=cl if sum(1 for f in fl[-6:] if f)>=4 else raw
    z=np.append(h[j:j+5],x[j]); md=np.median(z); mad=np.median(abs(z-md)); sg=1.486*mad
    fl0=0
    if sg==0:
        sg=1.253314*np.mean(abs(z-z.mean())); fl0=.0036 if len(set(z[:5]))==1 else 0
    exp_clip=abs(x[j]-md)>1.5*sg and abs(x[j]-md)>fl0
    lc=abs(lib[j]-x[j])>1e-12
    if exp_clip!=lc: print('j',j,'lib clip',lc,'mine',exp_clip,'mad',mad,'|v-md|',abs(x[j]-md),'1.5sg',1.5*sg,'window',z-md,'useclean',h is cl)
    fl.append((1 if x[j]>md else -1) if lc else 0)
print('====');print('window detail')
pc=clip(s['prev_current'][q-1],s['prev_new_i_sig'][q-1],s['outlier_max_index'][q-1],x)[0]; print('clip(no floor) diff',np.abs(pc-lib).max())
from cur import loess,gm,mins,smk
y=np.append(s['prev_outlier_removed_curr'][q-1],lib); f=loess(y)
T10=s['time_10sec_arr'][q].astype(np.int64); print('span90',T10[89]-T10[0],'dt',t[q]-t[q-1],'ts0-prevlast',(t[q]-290)-(t[q-1]))
for nm,v in (('loess+smk',mins(smk(gm(f[60:]),s['prev_mov_median_curr'][q-1]))),('loess',mins(gm(f[60:]))),('none+smk',mins(smk(gm(lib),s['prev_mov_median_curr'][q-1]))),('none',mins(gm(lib)))): print(nm,np.abs(v-g['tran_inA_1min'][q]).max())
print('kt minute',k['one'][q]-g['tran_inA_1min'][q])
