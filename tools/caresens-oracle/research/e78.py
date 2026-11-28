from e75 import ctxp,pre,rng
import numpy as np
np.seterr(all='ignore')
print('=====')
def sd(x): return x.std(ddof=1)
def pred(w,mode):
    w=w.astype(float);i=int(np.argmax(w));mn=int(np.argmin(w))
    if mode=='nb': nb=[j for j in(i-1,i+1) if 0<=j<30]; ex=[i,max(nb,key=lambda q:w[q])]
    if mode=='01': ex=[0,1]
    if mode=='0..2': ex=[0,1,2][: 2 if i==0 else 3]
    if mode=='01or12': ex=[0,1] if i==0 or w[0]>=w[2] else [1,2]
    if mode=='0i': ex=list(range(i+1))+([1] if i==0 else [])
    if mode=='i,i+1': ex=[i,i+1]
    if mode=='top2': ex=list(np.argsort(-w,kind='stable')[:2])
    a=sd(w)
    return i<=1 and w[i]>1.2*np.delete(w,i).mean() and a>1.5*sd(np.delete(w,ex)) and a<1.1*sd(np.delete(w,mn))
D=[]
for tr in range(12000):
    base=rng.integers(2800,15000); nz=rng.choice([0,3,30,100,500]); w=base+rng.normal(0,nz,30)
    k=rng.integers(1,4); js=rng.choice(np.arange(0,6),k,replace=False); w[js]+=base*rng.uniform(-.3,.6,k)
    w=np.clip(w,0,65000).astype('u2'); D.append((w,ctxp(w,pre)[0]==2))
for m in ('nb','01','0..2','01or12','i,i+1','top2'):
    bad=[(w,f) for w,f in D if pred(w,m)!=f]; print(m,len(bad),[ (f,w[:6].tolist()) for w,f in bad[:4]])
