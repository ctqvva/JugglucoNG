# full-resolution transitions of err16 conditions
from sc import *
import random, pickle
f=[n for n in co.DEBUG.names if n.startswith('err16') and co.DEBUG[n].shape==()]
R=random.Random(11); TR=[]
for it in range(160):
    lo=R.choice([50,60,70,80,100]); spec=dict(seed=R.randrange(1,10**9),readings=4320,start=1,lo=lo,hi=lo+R.choice([60,120,200,300]),
        knot_min=R.choice([10,30,60]),knot_max=R.choice([60,200,400]),noise=R.choice([5,12,25]),decay=R.choice([1,0.9,0.8,0.7,0.6,0.5,0.4]),
        slope100=R.uniform(3,7),eapp=R.choice([0.05,0.1]),temp_mode=R.choice(['const','vary']),temp_lo=3300,temp_hi=3600)
    spec['knot_max']=max(spec['knot_max'],2*spec['knot_min'])
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    C=g['err16_condi'].astype(int)
    for i in np.nonzero(np.diff(C,axis=0).any(1))[0]+1:
        TR.append(dict(run=it,i=int(i),seq=int(seq[i]),before=C[i-1].tolist(),after=C[i].tolist(),
            a={n:float(g[n][i-1]) for n in f},b={n:float(g[n][i]) for n in f}))
pickle.dump(TR,open('err16_tr.pkl','wb')); print(len(TR))
