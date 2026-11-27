from e71 import ev
import numpy as np, itertools
print('=====')
def bis(mk,a,b):
    fa=ev(mk(a));fb=ev(mk(b))
    if fa==fb: return None
    while abs(b-a)>1:
        m=(a+b)//2
        if ev(mk(m))==fa: a=m
        else: b=m
    return mk(a) if fa==2 else mk(b)   # last flagged
rng=np.random.default_rng(1); L_=[]
for tr in range(60):
    base=rng.integers(3000,20000); w=np.full(30,base); k=rng.integers(1,4)
    M=int(base*rng.uniform(1.6,3)); w[0]=M
    js=rng.choice(np.arange(2,30),k,replace=False); w[js]=(base+(M-base)*rng.uniform(0,.8,k)).astype(int)
    w[1]=int(base+(M-base)*rng.choice([0,0,.2,.5,.7,.9]))
    if tr%3==0: w=w+rng.integers(0,base//20,30)
    j=js[0]
    def mk(x,w=w,j=j): v=w.copy(); v[j]=x; return v
    r=bis(mk,int(base),int(M-1))
    if r is not None: L_.append(r)
print(len(L_))
def stat(w):
    w=w.astype(float);o={}
    m=w.mean();sd=w.std(ddof=1); o['z0']=(w[0]-m)/sd; o['z1']=(w[1]-m)/sd; r=np.delete(w,0);o['e0']=(w[0]-r.mean())/r.std(ddof=1); r2=w[2:]; o['r2']=(w[0]-r2.mean())/r2.std(ddof=1); o['cv2']=r2.std(ddof=1)/r2.mean(); o['cv29']=r.std(ddof=1)/r.mean(); o['cv30']=sd/m; o['ratio']=w[0]/r.mean(); o['w1']=(w[1]-np.median(w))/(w[0]-np.median(w)); d=np.diff(w); o['sdd']=d.std(ddof=1)/sd; return o
np.save('dt2.npy',np.array(L_))
for w in L_[:24]: print({k:round(float(v),4) for k,v in stat(w).items()})
