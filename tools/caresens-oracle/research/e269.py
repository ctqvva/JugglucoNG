from sc import *
import make_fixtures as mf
spec=mf.DEVELOPMENT['life-lot2-sensitivity-loss']
seq,t,adc,T=[np.array(x) for x in scenarios.generate(**spec)]; adc=adc.astype(np.int64); t=t.astype(np.int64)
d=co.default_device()
for k in ('slope100','vref','eapp'): d[k][0]=dict(scenarios.DEFAULTS,**spec)[k]
def go(label,mod):
    a=adc.copy(); s2=seq.copy(); t2=t.copy(); mod(a,s2,t2)
    c,o,g,s,_=REF.run(d,co.records(s2,t2,a.astype('u2'),T)); o=o[:,0]; g=g[:,0]
    e=(o['errcode']&16)!=0; ch=np.nonzero(np.diff(e.astype(int)))[0]+1
    print(label,[(int(i),int(e[i]),g['err16_condi'][i].tolist()) for i in ch[:6]])
go('none',lambda a,s,t:None)
def rej(a,s,t): a[3000,0]=a[3000,0]*6+3000; a[3000,1]=a[3000,1]*2
go('reject@3000 (cond off there)',rej)
def rej2(a,s,t): a[2700,0]=a[2700,0]*6+3000; a[2700,1]=a[2700,1]*2
go('reject@2700 (cond on)',rej2)
def gap(a,s,t): s[3000:]+=1; t[3000:]+=300
go('gap@3000',gap)
