import craft
from craft import *
spec=dict(BASE,readings=2600)
seq,t,adc0,T=[np.array(x) for x in scenarios.generate(**spec)]; adc0=adc0.astype(np.int64)
def sim(av,dev):
    a=adc0.copy(); a[300:2500]=av
    cc,o,g,_,s=REF.run(dev,co.records(seq,t,a.astype('u2'),T),snapshots=True); return o[:,0],g[:,0]
def bis(dev,pred,lo,hi):
    while hi-lo>1:
        m=(lo+hi)//2; o,g=sim(m,dev)
        if pred(o,g): lo=m
        else: hi=m
    o1,g1=sim(lo,dev); o2,g2=sim(hi,dev); return o1,g1,o2,g2
for sl,ea in ((3.5226,0.10067),(7.0,0.10067),(3.5226,0.06)):
    d=co.default_device(); d['slope100'][0]=sl; d['eapp'][0]=ea
    for nm,k,i in (('D1',1,1000),('D2',2,1000),('D4',4,2200),('D0',0,1000)):
        o1,g1,o2,g2=bis(d,lambda o,g:g['err4_delay_condi'][i][k]==1,1000,5000)
        print('slope',sl,'eapp',ea,nm,'@',i,'on: 5min %.4f corr %.4f glu %.3f init %.3f | off: 5min %.4f corr %.4f glu %.3f init %.3f'%(
            g1['tran_inA_5min'][i],g1['out_iir'][i],o1['result_glucose'][i],g1['init_cg'][i],g2['tran_inA_5min'][i],g2['out_iir'][i],o2['result_glucose'][i],g2['init_cg'][i]), 'drift %.4f'%g1['out_drift'][i])
