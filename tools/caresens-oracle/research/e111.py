from ref import *
from gen import mkwo
np.seterr(all='ignore')
N=4320
for k,f in enumerate((.3,.6,.9)):
    dec=np.linspace(1,f,N)[:,None]; w=mkwo(N,k,80,250); compare('decay%.1f'%f,(2756+(w-2756.)*dec).clip(0,65535).astype('u2'))
rng=np.random.default_rng(5); sq=np.cumsum(rng.choice([1]*20+[2,3,7,40],N)); dec=np.linspace(1,.4,N)[:,None]; w=mkwo(N,1,80,250)
compare('decay gaps',(2756+(w-2756.)*dec).clip(0,65535).astype('u2'),seq=sq)
