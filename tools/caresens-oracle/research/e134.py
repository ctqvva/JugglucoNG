from ref import *
from gen import mkwo
np.seterr(all='ignore')
rng=np.random.default_rng(0)
for k in range(4):
    N=1500; sq=np.cumsum(rng.choice([1]*30+[2,3,5,6,7,40],N)); t=BASE+300*sq+rng.integers(-[0,3,20,40][k],[0,3,20,40][k]+1,N)
    R,r,o,g,s=compare('gaps%d'%k,mkwo(N,k,noise=[12,50,100,30][k]),seq=sq,t=t)
    tl=o['trendrate']; 
    if k==0:
        Rr=Ref(dev()); 
        for i in range(60):
            m=Rr.step(int(sq[i]),int(t[i]),mkwo(N,k)[i],34.)
            if abs(m['tr']-tl[i])>1e-9: print(sq[i],'lib',tl[i],'mine',m['tr'],'Sq',Rr.Sq)
