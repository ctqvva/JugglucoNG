from e89 import multi,N0
import numpy as np
np.set_printoptions(linewidth=220,precision=5,suppress=True)
print('=====')
W=np.full((N0,30),30000)
def fl(prev,cur,tg=600):
    X=W.copy(); X[-1]=prev; res,oo,gg,a=multi(X,np.asarray(cur),34.,35.,tg); return np.concatenate([oo['workout'][k].astype(int) for k in range(res-1)])
t=np.arange(10,301,10)
D=8000
for nm in ('p0','p1','p2','p012','c0','c012'):
    p_=np.full(30,30000);c=np.full(30,30000); 
    for k in nm[1:]: (p_ if nm[0]=='p' else c)[int(k)*10:int(k)*10+10]+=D
    v=(fl(p_,c)-30000.)/D; q=np.polyfit(t,v,3); P=lambda x:np.polyval(q,x); dP=np.polyder(q); ddP=np.polyder(dP)
    print(nm,'fit',np.abs(P(t)-v).max()*D,'val -50,350',P(-50),P(350),'slope',np.polyval(dP,[-50,350])*100,'curv',np.polyval(ddP,[-50,350])*1e4)
