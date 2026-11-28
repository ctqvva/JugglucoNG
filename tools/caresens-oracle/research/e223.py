from sc import *
import make_fixtures as mf
from err16lib import *
for name in ('gaps-and-jitter','holdout-gaps-spikes','contact-spikes'):
    spec=mf.DEVELOPMENT.get(name) or mf.HOLDOUT[name]
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    P=g['err16_CGM_plasma']; acc=np.nonzero(o['data_type']!=2)[0]
    rows=[]
    for n,i in enumerate(acc):
        if n<11: continue
        h=acc[n-11:n+1]
        rows.append((np.isnan(P[i]), t[h[-1]]-t[h[0]], seq[h[-1]]-seq[h[0]], np.max(np.diff(t[h])), np.max(np.diff(seq[h])), t[h[-1]]-t[h[-2]]))
    r=np.array(rows,float)
    for col,nm in ((1,'tspan'),(2,'seqspan'),(3,'max dt'),(4,'max dseq'),(5,'last dt')):
        print(name,nm,'defined max',r[r[:,0]==0,col].max(),'nan min',r[r[:,0]==1,col].min() if (r[:,0]==1).any() else None)
    # rejected records: P at rejected
    rej=np.nonzero(o['data_type']==2)[0]; print('  P at rejected nan?',np.isnan(P[rej]).mean() if len(rej) else None)
