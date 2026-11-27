from e89 import multi,N0
import numpy as np
print('=====')
W=np.full((N0,30),30000); t=np.arange(10,301,10.); L=400.; u=t+50
def slope(g,c):
    p_=np.repeat(np.array(g),10).astype(int); cc=np.full(30,c)
    X=W.copy(); X[-1]=p_; res,oo,gg,a=multi(X,cc,34.,35.,600); y=np.concatenate([oo['workout'][k].astype(float) for k in range(res-1)])
    a_=g[2];C=c; f0=a_+(a_-C)/(2*L**3)*(u**3-3*L*u**2); f1=u+L/(2*L**3)*(u**3-3*L*u**2)
    s=np.dot(f1,y-f0)/np.dot(f1,f1); return s*100, np.abs(f0+s*f1-y).max()
lin=lambda g,c: .162731*g[0]-1.041499*g[1]+.80777*g[2]+.070989*c
for g,c in (([30000,30000,30000],30000),([40000,30000,30000],30000),([40000,40000,30000],30000),([20000,30000,40000],50000),([40000,20000,30000],35000),([30000,32000,31000],20000),([25000,40000,20000],45000)):
    s,e=slope(g,c); print(g,c,'s',round(s,4),'lin',round(lin(g,c)/1,4),'res',e)
