from e75 import ctxp,pre
import numpy as np
print('=====')
def ev(w): return ctxp(np.array(w,'u2'),pre)[0]
def bd(mk,a,b):
    fa,fb=ev(mk(a)),ev(mk(b))
    if fa==fb: return (fa,None)
    while abs(b-a)>1:
        m=(a+b)//2
        if ev(mk(m))==fa: a=m
        else: b=m
    return b
def W(base=10000,**k):
    w=np.full(30,base)
    for a,v in k.items(): w[int(a[1:])]=v
    return w
for M in (12500,13000,15000,20000,30000): print(M,'w5',bd(lambda x:W(_0=M,_5=x),9999,2000),'w1',bd(lambda x:W(_0=M,_1=x),9999,2000),'w1 max0, M at1 low w0',bd(lambda x:W(_1=M,_0=x),9999,2000),'two low 5,9',bd(lambda x:W(_0=M,_5=x,_9=x),9999,2000),'w1 high14000,w5',bd(lambda x:W(_0=M,_1=min(M,11000),_5=x),9999,2000))
