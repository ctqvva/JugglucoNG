from e36 import *
from collections import Counter
rr=np.random.default_rng(4);C=Counter()
z=np.zeros(60)
for k in range(400):
    p=rr.normal(0,1,3); b=go(p,z)
    J=tuple(np.round([(go(p+1e-5*(np.arange(3)==i),z)-b)[0]/1e-5*112 for i in range(3)],3)+0.0); C[J]+=1
for j,c in C.most_common(12): print(np.array(j),c)
