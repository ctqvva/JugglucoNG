from gen import *
from collections import Counter
def rep(nm,wo,T=34.,d=None,seq=None,t=None):
    r,o,g,a=run(dev() if d is None else d,wo,T,seq=seq,t=t)
    c=Counter(o['errcode'].tolist()); fl={k:int((g['error_code%d'%k]>0).sum()) for k in(1,2,4,8,16,32)}; f128=int((g['err128_flag']>0).sum())
    print(nm,dict(c),{k:v for k,v in fl.items() if v},'128:',f128,'data_type',dict(Counter(o['data_type'].tolist())), 'first',{k:int(np.argmax(g['error_code%d'%k]>0))+1 for k in(1,2,4,8,16,32) if fl[k]}); return r,o,g
N=4320
rep('normal',mkwo(N,1)); rep('wide',mkwo(N,2,40,400)); rep('noise100',mkwo(N,1,noise=100)); rep('noise300',mkwo(N,1,noise=300)); rep('noise1000',mkwo(N,1,noise=1000)); rep('noise3000',mkwo(N,1,noise=3000))
for l in (5,20,30,40,60,100,300,600,900,1500): rep('level%d'%l,mkwo(N,1,level=l))
dec=np.linspace(1,.3,N)[:,None]; w=mkwo(N,1,80,250); rep('decay',(2756+(w-2756.)*dec).clip(0,65535).astype('u2'))
w=mkwo(N,1,80,250).astype(float); w[2000:]=2756+(w[2000:]-2756)*.3; rep('drop30% at 2000',w.astype('u2'))
w=mkwo(N,1,80,250).astype(float); w[2000:]=2756+(w[2000:]-2756)*2.5; rep('jump x2.5',w.astype('u2'))
w=mkwo(N,1,80,250); w[1000:1300]=2800; rep('flat low 300',w)
w=mkwo(N,1,80,250); w[1000:1300]=w[1000]; rep('flat frozen',w)
rng=np.random.default_rng(0); w=mkwo(N,1,80,250).astype(int); w+=rng.integers(-1,2,w.shape)*(rng.random((N,1))<.2)*800; rep('bursts',w.clip(0,65535).astype('u2'))
sq=np.cumsum(rng.choice([1,1,1,1,2,3,7],N)); rep('gaps',mkwo(N,1)[:N],seq=sq)
