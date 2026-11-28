from ref import *
from gen import mkwo
np.seterr(all='ignore')
for nz in (200,300,600): compare('noise%d'%nz,mkwo(1500,1,noise=nz))
rng=np.random.default_rng(0)
for k in range(6):
    N=800; sq=np.cumsum(rng.choice([1,1,1,1,1,1,1,1,1,1,1,1,1,2,3,5,6,7],N)); t=BASE+300*sq+rng.integers(-[0,3,20,100,200,400][k]-1,[0,3,20,100,200,400][k]+1,N); compare('gaps%d'%k,mkwo(N,k,noise=[12,50,100,200,400,300][k]),seq=sq,t=t)
