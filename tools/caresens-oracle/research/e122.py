from ref import *
from gen import mkwo
np.seterr(all='ignore')
N=4320
for k,(f,lo,hi) in enumerate(((.3,80,250),(.6,80,250),(.8,80,250),(.5,150,400),(.5,100,110),(.5,40,300))):
    dec=np.linspace(1,f,N)[:,None]; w=mkwo(N,k,lo,hi); compare('decay%.1f %d-%d'%(f,lo,hi),(2756+(w-2756.)*dec).clip(0,65535).astype('u2'))
