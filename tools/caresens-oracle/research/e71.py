from e67 import *
print('=====')
rng=np.random.default_rng(0)
def ev(w,pre=None): X=np.full((12,30),10000); X[-1]=w; r,o,g,a=run(dev(),X.astype('u2'),34.); return int(o['data_type'][-1])
def rest(): return 10000+rng.permutation(30)*3
F=[]
for i in range(30):
    for j in range(30):
        if i==j: continue
        w=rest(); w[i]=30000; w[j]=25000
        if ev(w)==2: F.append((i,j))
print(len(F),F[:60])
F3={}
for i,j,k in itertools.permutations(range(6),3) if False else []: pass
import itertools
T=[]
for i,j,k in itertools.permutations(range(7),3):
    w=rest(); w[i]=30000;w[j]=25000;w[k]=20000
    if ev(w)==2: T.append((i,j,k))
print(len(T),T[:100])
T=[]
for ps in itertools.permutations(range(6),4):
    w=rest(); w[list(ps)]=[30000,25000,20000,15000]
    if ev(w)==2: T.append(ps)
print(len(T),T[:100])
for k4 in (10500,11000,11500,12000,12500,13000,14000): 
    w=rest(); w[[0,1,2]]=[30000,25000,20000]; w[10]=k4; print(k4,ev(w),end=' ')
