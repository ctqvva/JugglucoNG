from gen import *
from collections import Counter
rng=np.random.default_rng(3); C=Counter()
for rep in range(3):
    N=1500; sq=np.arange(1,N+1); t=BASE+300*sq+rng.integers(-40,41,N)
    w=mkwo(N,rep); r,o,g,a,s=run(dev(),w,34.,seq=sq,t=t,snap=True)
    for i in range(12,N):
        old=s['smooth_sig_in'][i-1]; new=s['smooth_sig_in'][i]; b=np.append(old[1:],o['result_glucose'][i])
        moved=bool(np.any(np.abs(b-new)>1e-9)); T=s['smooth_time_in'][i].astype(np.int64)
        d=np.diff(T); C[(moved,int(d.max()>=330),int(d.min()<=270), int(T[9]-T[0]>2700))]+=1
        if moved: C[('maxd',int(d.max()))]+=0
for k,v in sorted(C.items(),key=lambda z:str(z[0])): print(k,v)
mx=[];mn=[]
for rep in range(3):
    N=1500; sq=np.arange(1,N+1); t=BASE+300*sq+rng.integers(-40,41,N)
    w=mkwo(N,rep); r,o,g,a,s=run(dev(),w,34.,seq=sq,t=t,snap=True)
    for i in range(12,N):
        old=s['smooth_sig_in'][i-1]; new=s['smooth_sig_in'][i]; b=np.append(old[1:],o['result_glucose'][i]); moved=bool(np.any(np.abs(b-new)>1e-9)); T=s['smooth_time_in'][i].astype(np.int64); d=np.diff(T)
        (mx if moved else mn).append((d.max(),d.min(),T[9]-T[0]))
mx=np.array(mx);mn=np.array(mn); print('moved: maxd max',mx[:,0].max(),'mind min',mx[:,1].min(),'span',mx[:,2].min(),mx[:,2].max()); print('not moved maxd min',mn[:,0].min(), 'span',mn[:,2].min(),mn[:,2].max(), 'not moved with maxd<=? count',(mn[:,0]<=mx[:,0].max()).sum())
