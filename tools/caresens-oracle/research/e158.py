from diag import *
spec={'seed': 755202396, 'readings': 1500, 'start': 1, 'slope100': 5.288594948110065, 'vref': 1.5677061418258051, 'eapp': 0.06837379743000922, 'lo': 40, 'hi': 120, 'knot_min': 3, 'noise': 50, 'temp_mode': 'step', 'temp_lo': 3230, 'decay': 0.7, 'gap_permille': 10, 'gap_max': 3, 'jitter': 30, 'spike_permille': 5, 'clock_jump_at': 200, 'clock_jump': 40000, 'knot_max': 6, 'temp_hi': 3530}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
e=(k['err']^o['errcode'])&96; q=np.argmax(e>0); print('first err diff',q+1,seq[q],'lib',o['errcode'][q],'mine',k['err'][q])
for z in range(q-2,q+2): print(z+1,seq[z],'dt',t[z]-t[z-1],'dtype',o['data_type'][z],'err',o['errcode'][z],k['err'][z],'buf',s['err32_buff_23'][z],s['err32_buff_60'][z],s['err32_n'][z],s['err32_prev_seq'][z])
tr=np.abs(np.nan_to_num(k['tr'])-o['trendrate'])*(o['data_type']==0); q=np.argmax(tr>1e-7); print('first trend diff',q+1,seq[q],'lib',o['trendrate'][q],'mine',k['tr'][q],'S',s['smooth_sig_in'][q][3:],'T',np.diff(s['smooth_time_in'][q].astype(np.int64)))
print('====')
for z in range(q-9,q+1): print(z+1,seq[z],'dtype',o['data_type'][z],'G %.2f'%o['result_glucose'][z],'trend',o['trendrate'][z],'mine',k['tr'][z],'err',o['errcode'][z])
