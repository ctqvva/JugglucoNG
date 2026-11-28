from gen import *
from collections import Counter
KS=np.array([-1.6,2.7,6.6,5.6,5.4,3.9,-1.6])/21
rng=np.random.default_rng(3); C=Counter(); ex=[]
for rep in range(4):
    N=1500; sq=np.cumsum(np.where(rng.random(N)<.08,rng.integers(2,9,N),1))
    w=mkwo(N,rep); r,o,g,a,s=run(dev(),w,34.,seq=sq,snap=True)
    for i in range(12,N):
        old=s['smooth_sig_in'][i-1]; new=s['smooth_sig_in'][i]; b=np.append(old[1:],o['result_glucose'][i])
        moved=tuple(np.nonzero(np.abs(b-new)>1e-9)[0]); T=s['smooth_time_in'][i].astype(np.int64); span=(T[9]-T[0])//300
        Qs=tuple(np.diff(T[3:])//300)
        C[(span,moved)]+=1
        if len(ex)<12 and moved and moved!=(3,4,5,6,7,8): ex.append((sq[i],moved,np.diff(T)//300,s['smooth_f_rep_in'][i-1],s['smooth_f_rep_in'][i]))
for k,v in sorted(C.items(),key=lambda z:-z[1])[:30]: print(k,v)
for e in ex: print(e)
