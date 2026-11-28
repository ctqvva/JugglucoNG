from e89 import multi,N0
import numpy as np
print('=====')
W=np.full((N0,30),30000)
def curve(g,c,tg):
    p_=np.repeat(np.array(g),10).astype(int); cc=np.full(30,c)
    X=W.copy(); X[-1]=p_; res,oo,gg,a=multi(X,cc,34.,35.,tg)
    ts=np.concatenate([oo['measurement_time_standard'][k]-BASE-300*N0-290+10*np.arange(30) for k in range(res-1)]).astype(float)
    y=np.concatenate([oo['workout'][k].astype(float) for k in range(res-1)]); return ts,y
from lab import BASE
for tg in (480,500,600,700,900,1200,1379):
    S=[];E=[]
    for i,D in ((0,20000),(1,-20000),(2,-20000),(3,20000)):
        g=[30000]*3;c=30000
        if i<3: g[i]+=D
        else: c+=D
        ts,y=curve(g,c,tg); y=(y-30000)/D; L=tg-250+50.; u=ts+50
        a=1. if i==2 else 0.; C=1. if i==3 else 0.
        f0=a+(a-C)/(2*L**3)*(u**3-3*L*u**2); f1=u+L/(2*L**3)*(u**3-3*L*u**2); s=np.dot(f1,y-f0)/np.dot(f1,f1); S.append(s*100); E.append(np.abs(f0+s*f1-y).max()*abs(D))
    S=np.array(S); al=-S[0]; be=-S[1]+S[0]*0-al*1+0; 
    al=-S[0]; be=al-S[1]; ga=S[3]*L/100
    print(tg,'L',L,'slopes',S.round(6),'secant w',round(al,5),round(be,5),round(ga,5),'sum',round(-al+be+ga+0,5),'fit',max(E), 'times',ts[[0,29,-1]])
