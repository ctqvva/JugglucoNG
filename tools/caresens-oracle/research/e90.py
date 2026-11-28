from e89 import multi,N0
import numpy as np
np.set_printoptions(linewidth=220,precision=3,suppress=True)
print('=====')
W=np.full((N0,30),20000)
def fl(prev,cur,tg=600):
    X=W.copy(); X[-1]=prev; res,oo,gg,a=multi(X,np.asarray(cur),34.,35.,tg); return np.concatenate([oo['workout'][k].astype(int) for k in range(res-1)])
b=fl(np.full(30,20000),np.full(30,30000)); print('step',b)
for side in (0,1):
    for j in (0,1,10,25,27,28,29) if side==0 else (0,1,2,4,10,29):
        p_=np.full(30,20000);c=np.full(30,20000); (p_ if side==0 else c)[j]+=3500
        v=fl(p_,c)-20000; print('prev' if side==0 else 'cur',j,v)
