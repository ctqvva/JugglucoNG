from e89 import multi,N0
import numpy as np
from scipy.interpolate import CubicSpline
np.set_printoptions(linewidth=220,precision=5,suppress=True)
print('=====')
W=np.full((N0,30),20000)
def fl(prev,cur,tg=600):
    X=W.copy(); X[-1]=prev; res,oo,gg,a=multi(X,np.asarray(cur),34.,35.,tg); return np.concatenate([oo['workout'][k].astype(int) for k in range(res-1)])
D=40000; B=[]
for nm in ('p0','p1','p2','c0'):
    p_=np.full(30,20000);c=np.full(30,20000)
    k=int(nm[1]); (p_ if nm[0]=='p' else c)[k*10:k*10+10]+=D
    B.append((fl(p_,c)-20000.)/D)
B=np.array(B); np.save('fillB.npy',B); print(B[:,[0,1,28,29]])
t=np.arange(10,301,10)
for nd in ([-250,-150,-50,350],[-245,-145,-45,355]):
    for bc in ('natural','not-a-knot',((1,0),(2,0)),'clamped'):
        M=np.array([CubicSpline(nd,np.eye(4)[i],bc_type=bc)(t) for i in range(4)]); print(nd,bc,np.abs(M-B).max()*D)
