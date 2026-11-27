from e67 import *
import itertools
print('=====')
base=np.full((12,30),10000)
def fl(ps,hi=30000,lo=10000,n=12):
    X=base.copy(); X[-1]=lo; X[-1,list(ps)]=hi; r,o,g,a=run(dev(),X.astype('u2'),34.); return int(o['data_type'][-1])==2
S=[ps for k in range(1,8) for ps in itertools.combinations(range(8),k) if fl(ps)]; print(len(S)); print(S[:80])
# different heights
def vec(v): X=base.copy(); X[-1,:len(v)]=v; r,o,g,a=run(dev(),X.astype('u2'),34.); return int(o['data_type'][-1])
for v in ([10000,10000,30000],[10000,11000,30000],[20000,30000,30000],[15000,30000],[30000,15000],[30000,20000,15000],[30000,20000,15000,12000],[30000,11000,10000,12000],[30000,10000,10000,10000,30000],[30000]+[10000]*20+[12500],[30000]+[10000]*27+[12500]*2,[8000,30000,10000],[8000,30000,9000,10000],[29000,30000,29000]): print(v[:6],vec(v))
