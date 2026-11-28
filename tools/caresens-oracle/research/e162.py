from sc import *
spec={"seed": 779510387, "readings": 1500, "start": 1, "slope100": 7.49389625216919, "vref": 1.4680401380931547, "eapp": 0.07175060874959654, "lo": 120, "hi": 470, "knot_min": 30, "noise": 50, "temp_mode": "step", "temp_lo": 3411, "decay": 0.9, "gap_permille": 40, "gap_max": 12, "jitter": 0, "spike_permille": 20, "clock_jump_at": -1, "clock_jump": 2000, "knot_max": 150, "temp_hi": 3711}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
w=adc[6].astype(float); i=np.argmax(w); print(w[:5],i)
nb=[j for j in (i-1,i+1) if 0<=j<30]; j=max(nb,key=lambda q:w[q]); mn=np.argmin(w)
sd=lambda x:x.std(ddof=1)
print('ratio',w[i]/np.delete(w,i).mean(),'sd ratio',sd(w)/sd(np.delete(w,[i,j])),'low ratio',sd(w)/sd(np.delete(w,mn)), 'max',w.max())
print('vendor counts',s['idx'][:8],s['contact_err_start_seq'][:8],s['prev_contact_err_algo_seq'][:8])
# experiment: same record at different context
from gen import run as grun, dev as gdev
