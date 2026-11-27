from e31 import *
from e33 import sm
from e20 import mm,tm
import itertools
Yn=DS
def mm30(q): return mm(q)
def cand():
    yield 'full',lambda f,p3:[tm(z) for z in mm(f[60:]).reshape(5,6)]
    yield 'full90',lambda f,p3:[tm(z) for z in mm(f)[60:].reshape(5,6)]
    yield 'p3full',lambda f,p3:[tm(z) for z in mm(np.append(p3,f[60:]))[3:].reshape(5,6)]
    yield 'median',lambda f,p3:[np.median(z) for z in f[60:].reshape(5,6)]
    yield 'mean',lambda f,p3:[np.mean(z) for z in mm(f[60:]).reshape(5,6)]
    yield 'p3mean',lambda f,p3:[np.mean(z) for z in mm(np.append(p3,f[60:]))[3:].reshape(5,6)]
    yield 'tmgrp',lambda f,p3:[tm(mm(z)) for z in f[60:].reshape(5,6)]
    yield 'p3shift',lambda f,p3:[tm(z) for z in np.append(p3,mm(f[60:]))[:30].reshape(5,6)]
    yield 'p3+6',lambda f,p3:[tm(np.append(p3 if k==0 else mm(f[60:])[6*k-3:6*k],mm(f[60:])[6*k:6*k+6])) for k in range(5)]
p3=st['prev_mov_median_curr'][0]-base
for n,fn in cand(): print(n,np.sqrt(np.mean([(np.array(fn(sm(y),p3))-t[:5])**2 for y,t in DS],axis=0))*1e6)
