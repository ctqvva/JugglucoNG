from ref import *
from gen import mkwo
np.seterr(all='ignore')
N=2100
for k,(lo,hi) in enumerate(((40,300),(200,560),(300,700),(20,90))):
    compare('%d-%d'%(lo,hi),mkwo(N,k,lo,hi))
