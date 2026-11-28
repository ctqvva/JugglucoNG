from ref import *
from gen import mkwo
np.seterr(all='ignore')
N=4320
compare('wide',mkwo(N,2,40,480,noise=60))
compare('noise1000',mkwo(N,1,noise=1000))
dec=np.linspace(1,.3,N)[:,None]; w=mkwo(N,1,80,250); compare('decay',(2756+(w-2756.)*dec).clip(0,65535).astype('u2'))
w=mkwo(N,1,80,250); w[1000:1300]=2800; compare('flat low',w)
compare('lev5',mkwo(N,1,level=5)); compare('lev1500',mkwo(N,1,level=1500))
