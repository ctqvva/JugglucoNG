from lab import *
np.set_printoptions(linewidth=190,precision=6,suppress=True)
def mm7(x,h=3):
    n=len(x); return np.array([np.median(x[max(0,i-h):i+h+1]) for i in range(n)])
def tm(x): return (np.sum(x)-np.min(x)-np.max(x))/(len(x)-2)
rng=np.random.default_rng(2)
d=dev(); wo=rng.normal(10000,30,(2,30)).astype('u2'); wo[1]=np.sort(wo[1])
r,o,g,a,s=run(d,wo[:1],34.0,snap=True)
x=g['tran_inA'][0]; m=mm7(x); print(m.reshape(5,6)); print(g['tran_inA_1min'][0]);print(m.reshape(5,6).mean(1),np.median(m.reshape(5,6),axis=1),[tm(q) for q in m.reshape(5,6)])
