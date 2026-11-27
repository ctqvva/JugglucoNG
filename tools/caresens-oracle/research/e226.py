# dataset of err16 debug scalars + condi, records with seq>=600, decaying-sensitivity runs
from sc import *
import random
f=[n for n in co.DEBUG.names if n.startswith('err16') and co.DEBUG[n].shape==()]
R=random.Random(11); X=[];Y=[];META=[]
for it in range(160):
    lo=R.choice([50,60,70,80,100]); spec=dict(seed=R.randrange(1,10**9),readings=4320,start=1,lo=lo,hi=lo+R.choice([60,120,200,300]),
        knot_min=R.choice([10,30,60]),knot_max=R.choice([60,200,400]),noise=R.choice([5,12,25]),decay=R.choice([1,0.9,0.8,0.7,0.6,0.5,0.4]),
        slope100=R.uniform(3,7),eapp=R.choice([0.05,0.1]),temp_mode=R.choice(['const','vary']),temp_lo=3300,temp_hi=3600)
    spec['knot_max']=max(spec['knot_max'],2*spec['knot_min'])
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    m=np.nonzero(seq>=600)[0][::3]
    X.append(np.stack([g[n][m] for n in f],1)); Y.append(g['err16_condi'][m]); META.append(np.stack([np.full(len(m),it),seq[m]],1))
np.savez('err16_ds.npz',X=np.concatenate(X),Y=np.concatenate(Y),M=np.concatenate(META),names=np.array(f))
Y=np.concatenate(Y); print(Y.shape, Y.sum(0))
