from e71 import ev
import numpy as np
print('=====')
def thr(mk,lo,hi):
    out=[];p_=None
    for x in range(lo,hi,5):
        v=ev(mk(x))
        if v!=p_: out.append((x,v)); p_=v
    return out
def W(**k):
    w=np.full(30,10000)
    for a,b in k.items(): w[int(a[1:])]=b
    return w
print('w0=30000,w2=x',thr(lambda x:W(_0=30000,_2=x),9000,31000))
print('w0=30000,w2=20000,w3=x',thr(lambda x:W(_0=30000,_2=20000,_3=x),9000,31000))
print('w0=30000,w2=25000,w3=x',thr(lambda x:W(_0=30000,_2=25000,_3=x),9000,31000))
print('w0=30000,w2=25000,w9=x',thr(lambda x:W(_0=30000,_2=25000,_9=x),9000,31000))
print('w0=30000,w2=25000,w3=20000,w1=x',thr(lambda x:W(_0=30000,_2=25000,_3=20000,_1=x),9000,31000))
print('w2=30000,w0=x',thr(lambda x:W(_2=30000,_0=x),9000,31000)); print('w2=30000,w1=x',thr(lambda x:W(_2=30000,_1=x),9000,31000)); print('w2=30000 w1,w0=x',thr(lambda x:W(_2=30000,_1=x,_0=x),9000,31000))
print('w0=14000,w2=x',thr(lambda x:W(_0=14000,_2=x),9000,15000))
