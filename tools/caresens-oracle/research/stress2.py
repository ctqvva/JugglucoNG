from stress import *
def runT(fn):
    seq,t,adc,T=base(); seq,t,adc,T=fn(seq,t,adc,T)
    c,o,g,s,_=REF.run(co.default_device(),co.records(seq,t,adc.astype('u2'),T)); o=o[:,0]
    e=o['errcode']; out={b:(int(np.nonzero(e&b)[0][0]),int((e&b!=0).sum())) for b in (1,2,4,8,16,32,64,128) if (e&b).any()}
    return out, np.unique(o['data_type'],return_counts=True)
def setT(v,a=500,b=700):
    def f(seq,t,adc,T): T=T.copy(); T[a:b]=v; return seq,t,adc,T
    return f
tests={
 'T=10':setT(10.),'T=20':setT(20.),'T=28':setT(28.),'T=40':setT(40.),'T=45':setT(45.),'T=50':setT(50.),'T=0':setT(0.),'T=-5':setT(-5.),
 'T jump 30<->38':lambda s,t,a,T:(s,t,a,np.where(np.arange(len(T))%2,30.,38.)),
 'T nan':setT(np.nan),
 'seq wrap 65535':lambda s,t,a,T:((s+64000)%65536,t,a,T),
 'time backwards':lambda s,t,a,T:(s,np.concatenate([t[:500],t[500:]-100000]),a,T),
 'dup seq':lambda s,t,a,T:(np.concatenate([s[:500],s[499:-1]]),t,a,T),
 'long wear 20d':None,
}
for k,f in tests.items():
    if f is None: continue
    print('%-16s'%k,*runT(f))
# long wear: start late seq
spec=dict(BASE,readings=1500,start=4000)
seq,t,adc,T=[np.array(x) for x in scenarios.generate(**spec)]
c,o,g,s,_=REF.run(co.default_device(),co.records(seq,t,np.array(adc,'u2'),T)); e=o[:,0]['errcode']
print('start 4000', {b:(int(np.nonzero(e&b)[0][0]),int((e&b!=0).sum())) for b in (1,2,4,8,16,32,64,128) if (e&b).any()}, 'seq range',seq[0],seq[-1])
