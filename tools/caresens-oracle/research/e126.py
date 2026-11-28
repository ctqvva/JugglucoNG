from gen import *
from collections import Counter
wo=mkwo(3000,1)
def go(sq,t=None,show=False):
    sq=np.array(sq); t=BASE+300*sq if t is None else np.array(t); N=len(sq)
    r,o,g,a,s=run(dev(),wo[:N],34.,seq=sq,t=t,snap=True); e=o['errcode']
    return e,g,s,o
rng=np.random.default_rng(0)
for p in (.02,.05,.1,.2,.4):
    sq=np.cumsum(np.where(rng.random(2500)<p,rng.integers(2,40,2500),1)); e,g,s,o=go(sq)
    print(p,Counter(e.tolist()),'e32',int((g['error_code32']>0).sum()))
# time-only jitter
sq=np.arange(1,1500); t=BASE+300*sq+rng.integers(-100,100,len(sq)); e,g,s,o=go(sq,t); print('jitter',Counter(e.tolist()))
t=BASE+300*sq; t[500:]+=7*86400; e,g,s,o=go(sq,t); print('time jump',Counter(e.tolist()))
t=BASE+300*sq; t[500:]-=7*86400; e,g,s,o=go(sq,t); print('time back',Counter(e.tolist()))
print('=====')
sq=np.arange(1,700)
for D in (300,600,1200,1380,1500,3000,6000,6900,7200,7500,10000,17000,18000,20000,86400):
    t=BASE+300*sq; t[500:]+=D; e,g,s,o=go(sq,t); nz=np.nonzero(e)[0]
    print(D,'n err',len(nz),'first',nz[0]+1 if len(nz) else None,'last',nz[-1]+1 if len(nz) else None,Counter(e.tolist()),'dtypes',Counter(o['data_type'].tolist()), 'buff',s['err32_buff_23'][520],s['err32_buff_60'][520],s['err32_buff_600'][520],s['err32_n'][520])
