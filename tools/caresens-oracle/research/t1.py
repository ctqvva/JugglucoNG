from kt import *
from gen import mkwo
np.seterr(all='ignore')
N=4320; T=34+1.5*np.sin(np.arange(N)/37)
cmp('normal',mkwo(N,1,T=T),T); cmp('restore@2000',mkwo(N,1,T=T),T,restore=2000)
cmp('lot2',mkwo(N,3,d=dev(eapp=.05,slope100=5.)),d=dev(eapp=.05,slope100=5.))
dec=np.linspace(1,.6,N)[:,None]; w=mkwo(N,1,80,250); cmp('decay',(2756+(w-2756.)*dec).clip(0,65535).astype('u2'))
cmp('noise300',mkwo(N,1,noise=300))
rng=np.random.default_rng(0); sq=np.cumsum(rng.choice([1]*30+[2,3,5,6,7,40],N)); t=BASE+300*sq+rng.integers(-40,41,N); cmp('gaps+jitter',mkwo(N,2),seq=sq,t=t)
sq=np.arange(1,1500); t=BASE+300*sq; t[700:]+=86400; cmp('err32',mkwo(1499,4),seq=sq,t=t)
rng=np.random.default_rng(6); wo=10000+rng.normal(0,30,(3000,30))+(np.arange(3000*30).reshape(3000,30)%997*4); cmp('contact',wo.astype('u2'))
cmp('badparam',mkwo(50,1),d=dev(slope100=9.))
