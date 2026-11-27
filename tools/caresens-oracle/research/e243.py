import craft
from craft import *
craft.DEV=co.default_device()
seq,t,adc,T=base()
for c,K in ((0.8,800),(1.25,800),(1.6,800)):
    a=adc.copy(); a[300:300+K]=adc_for(c)
    cc,o,g,_,s=REF.run(craft.DEV,co.records(seq,t,a.astype('u2'),T),snapshots=True); o=o[:,0]; g=g[:,0]
    C=g['err4_condi']; D=g['err4_delay_condi']
    on=[(k,int(np.nonzero(C[:,k])[0][0])) for k in range(5) if C[:,k].any()]
    print('c',c,'K',K,'C first on',on,'D first',[(k,int(np.nonzero(D[:,k])[0][0])) for k in range(5) if D[:,k].any()],'err4 first',np.nonzero(o['errcode']&4)[0][:1], 'flag arr sum at end of plateau',s['err4_delay_flag_arr'][300+K-1].sum())
