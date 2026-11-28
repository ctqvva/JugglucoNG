from e89 import multi,N0
import numpy as np
np.set_printoptions(linewidth=220,precision=4,suppress=True)
print('=====')
W=np.full((N0,30),20000)
def fl(prev,cur,tg=600):
    X=W.copy(); X[-1]=prev; res,oo,gg,a=multi(X,np.asarray(cur),34.,35.,tg); return np.concatenate([oo['workout'][k].astype(int) for k in range(res-1)]) if res>1 else None
# step position scan in prev and cur
D=2000
for nm in('prev','cur'):
    last=None
    for j in range(30,-1,-1):
        p_=np.full(30,20000);c=np.full(30,20000); (p_ if nm=='prev' else c)[j:]+=D
        v=fl(p_,c)
        if v is None: print(nm,j,'none'); continue
        v=(v-20000)/D; print(nm,j,v[[0,5,15,25,29]], '' if last is None else (v-last)[[0,5,15,25,29]]); last=v
