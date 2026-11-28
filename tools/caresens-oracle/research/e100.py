from e89 import multi,N0
import numpy as np
print('=====')
W=np.full((N0,30),30000); t=np.arange(10,301,10.); L=400.; u=t+50
def fl(prev,cur,tg=600):
    X=W.copy(); X[-1]=prev; res,oo,gg,a=multi(X,np.asarray(cur),34.,35.,tg); return np.concatenate([oo['workout'][k].astype(int) for k in range(res-1)])
S=[]
for i,D in ((0,20000),(1,-20000),(2,-20000),(3,20000),(1,20000),(2,15000)):
    p_=np.full(30,30000);c=np.full(30,30000); (p_ if i<3 else c)[(i%3)*10:(i%3)*10+10]+=D
    y=(fl(p_,c)-30000.)/D; a=1. if i==2 else 0.; C=1. if i==3 else 0.
    f0=a+(a-C)/(2*L**3)*(u**3-3*L*u**2); f1=u+L/(2*L**3)*(u**3-3*L*u**2)
    s=np.dot(f1,y-f0)/np.dot(f1,f1); print(i,D,'s*100=%.6f'%(s*100),np.abs(f0+s*f1-y).max()*abs(D))
