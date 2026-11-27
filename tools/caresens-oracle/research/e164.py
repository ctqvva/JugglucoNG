from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=6,suppress=True)
spec={"seed": 769894003, "readings": 4320, "start": 1, "slope100": 4.196787406260732, "vref": 1.431392060662356, "eapp": 0.10158600551933888, "lo": 120, "hi": 370, "knot_min": 60, "noise": 5, "temp_mode": "const", "temp_lo": 3186, "decay": 0.9, "gap_permille": 10, "gap_max": 3, "jitter": 0, "spike_permille": 0, "clock_jump_at": 200, "clock_jump": 2000, "knot_max": 600, "temp_hi": 3486}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
od=np.abs(k['one']-g['tran_inA_1min']).max(1)*(o['data_type']!=2); q=np.argmax(od>1e-9); print('first minute diff',q+1,seq[q])
for z in range(q-2,q+1): print(z+1,seq[z],'dt',t[z]-t[z-1],k['one'][z]-g['tran_inA_1min'][z], 'clean diff', (s['prev_outlier_removed_curr'][z][30:]-0)[:3], 'flags',s['outlier_max_index'][z])
print('====')
from e16 import clip
x=g['tran_inA'][q]; pc=clip(s['prev_current'][q-1],s['prev_new_i_sig'][q-1],s['outlier_max_index'][q-1],x)[0]
lib=s['prev_outlier_removed_curr'][q][30:]; print('clip diff',np.abs(pc-lib).max())
from cur import loess,gm,mins,smk
y=np.append(s['prev_outlier_removed_curr'][q-1],lib); f=loess(y)
print('loess->minutes diff', np.abs(mins(smk(gm(f[60:]),s['prev_mov_median_curr'][q-1]))-g['tran_inA_1min'][q]).max())
r=np.abs(y-f); print('median resid', np.median(r), np.sort(r)[44:46], 'ties?', len(np.unique(np.round(r,12))))
print('####')
jj=np.nonzero(np.abs(pc-lib)>1e-12)[0]; print('diff at',jj, 'x',x[jj],'mine',pc[jj],'lib',lib[jj])
raw=np.append(s['prev_current'][q-1],x); cl=np.append(s['prev_new_i_sig'][q-1],lib); j=jj[0]
z=raw[j:j+6]; md=np.median(z); mad=np.median(abs(z-md)); print('raw window',z,'md',md,'mad',mad,'1.486mad',1.486*mad,'|v-md|/sg',abs(x[j]-md)/(1.486*mad) if mad else None)
z2=cl[j:j+6]; md2=np.median(z2); print('clean window',z2, np.median(abs(z2-md2)))
print('flags prev',s['outlier_max_index'][q-1])
print('@@@@')
st=s[q-1:q].copy(); rec=co.records([seq[q]],[t[q]],adc[q:q+1].astype('u2'),[T[q]])
print('adc 3..12',adc[q][3:13])
for dv in (-6,-3,-1,1,2,3,4,6,10):
    r2=rec.copy(); r2['workout'][0][9]=adc[q][4]+dv
    c2,o2,g2,a2,_=REF.run(d,r2,state=st.copy())
    xx=g2[0,0]['tran_inA'][9]; cc=a2['prev_outlier_removed_curr'][0][39]
    print(dv,'clipped' if abs(xx-cc)>1e-12 else 'kept', cc-xx)
