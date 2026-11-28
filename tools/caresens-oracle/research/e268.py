from stress import *
import json
def go(name,mod,label,bits=(1,2,4,16)):
    seq,t,adc,T=base(); x=tf(name,adc,500,700); s2=seq.copy(); t2=t.copy()
    mod(x,s2,t2)
    c,o,g,s,_=REF.run(co.default_device(),co.records(s2,t2,x.astype('u2'),T)); o=o[:,0]
    out={}
    for b in bits:
        e=(o['errcode']&b)!=0; ch=np.nonzero(np.diff(e.astype(int)))[0]+1
        if e.any(): out[b]=[(int(i),int(e[i])) for i in ch[:5]]
    print('%-10s %-22s'%(name,label),out)
def none(x,s,t): pass
def rej(x,s,t): x[1000,0]=x[1000,0]*6+3000; x[1000,1]=x[1000,1]*2
def gap1(x,s,t): s[1000:]+=1; t[1000:]+=300
def tjump(x,s,t): t[1000:]+=900
for nm in ('noise200','high3','low30','step_down','sat'):
    for f,l in ((none,'none'),(rej,'reject@1000'),(gap1,'1 missing seq @1000'),(tjump,'time jump 900s @1000')):
        go(nm,f,l)
