from e67 import *
print('=====')
def ctxA(W): r,o,g,a=run(dev(),np.array(W).astype('u2'),34.); return int(o['data_type'][-1])
base=np.full((12,30),10000)
def thr(ps,W=base,lo=10000):
    a_,b_=lo,60000
    def f(h): X=W.copy(); X[-1,list(ps)]=h; return ctxA(X)==2
    if not f(b_): return None
    while b_-a_>1:
        m=(a_+b_)//2
        if f(m): b_=m
        else: a_=m
    return b_
for ps in ([0],[1],[0,1],[1,2],[0,1,2]): print(ps,thr(ps))
print('lo variants', [(lo,thr([0],np.full((12,30),lo),lo),thr([0],np.full((12,30),lo),lo)/lo) for lo in (3000,5000,8000,10000,15000,20000,30000)])
print('prev level',[(pv,thr([0],np.vstack([np.full((11,30),pv),np.full((1,30),10000)]))) for pv in (3000,8000,9900,10000,10100,12000,14000,20000)])
t0=thr([0]); 
for j in range(1,30):
    X=base.copy(); X[-1,j]+=100; t1=thr([0],X)
    if t1!=t0: print('elem',j,t1-t0 if t1 else None,end=' ')
print()
for j in range(0,30):
    X=base.copy(); X[-1,j:]+=100; print((j,thr([0],X)),end=' ')
