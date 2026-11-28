from sc import *
spec={"seed": 769894003, "readings": 4320, "start": 1, "slope100": 4.196787406260732, "vref": 1.431392060662356, "eapp": 0.10158600551933888, "lo": 120, "hi": 370, "knot_min": 60, "noise": 5, "temp_mode": "const", "temp_lo": 3186, "decay": 0.9, "gap_permille": 10, "gap_max": 3, "jitter": 0, "spike_permille": 0, "clock_jump_at": 200, "clock_jump": 2000, "knot_max": 600, "temp_hi": 3486}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); q=253
st=s[q-1:q]
def test(row,j=9):
    rec=co.records([seq[q]],[t[q]],np.array([row],'u2'),[T[q]]); c2,o2,g2,a2,_=REF.run(d,rec,state=st.copy())
    xx=g2[0,0]['tran_inA'][j]; cc=a2['prev_outlier_removed_curr'][0][30+j]; return 'clip' if abs(xx-cc)>1e-12 else 'keep'
base=adc[q].copy(); print('orig',base[:12],test(base))
r=base.copy(); r[:9]=5250; print('0..8 =5250',test(r))
r=base.copy(); r[:4]=5250+np.array([3,-2,4,-1]); print('vary 0..3',test(r))
print('prev record tail raw', (s['prev_current'][q-1]), 'clean',s['prev_new_i_sig'][q-1], 'flags',s['outlier_max_index'][q-1])
for j0 in range(0,4):
    r=base.copy(); r[j0]=5250; print('set',j0,'=5250',r[:10],test(r))
