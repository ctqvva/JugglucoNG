import craft
from craft import *
craft.DEV=co.default_device()
spec=dict(BASE,readings=2600)
seq,t,adc,T=[np.array(x) for x in scenarios.generate(**spec)]; adc=adc.astype(np.int64)
def tl(a,label):
    cc,o,g,_,s=REF.run(craft.DEV,co.records(seq,t,a.astype('u2'),T),snapshots=True); o=o[:,0]; g=g[:,0]
    C=g['err4_condi'].astype(int); D=g['err4_delay_condi'].astype(int)
    ch=np.nonzero((np.diff(C,axis=0)!=0).any(1)|(np.diff(D,axis=0)!=0).any(1))[0]+1
    print('==',label,'err4 first',np.nonzero(o['errcode']&4)[0][:1].tolist())
    for i in ch[:14]: print('   ',i,'seq',seq[i],'C',C[i].tolist(),'D',D[i].tolist(),'5min %.3f glu %.1f'%(g['tran_inA_5min'][i],o['result_glucose'][i]))
a=adc.copy(); a[300:2500]=adc_for(1.6); tl(a,'flat 1.6 300..2500')
a=adc.copy(); a[300:2500]=adc_for(0.8); tl(a,'flat 0.8')
a=adc.copy(); a[300:2500]=(a[300:2500]*0.25).astype(int); tl(a,'scaled 0.25 (varying low)')
a=adc.copy(); a[300:2500]=(a[300:2500]*0.4).astype(int); tl(a,'scaled 0.4')
