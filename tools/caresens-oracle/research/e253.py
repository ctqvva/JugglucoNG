exec(open('e251.py').read().split("for sl,ea in")[0])
for sl,ea in ((3.5226,0.10067),(7.0,0.10067),(3.5226,0.06)):
    d=co.default_device(); d['slope100'][0]=sl; d['eapp'][0]=ea
    for nm,k,i in (('C2',2,1000),('C3',3,1000),('C2late',2,2200),('C3late',3,2200)):
        o1,g1,o2,g2=bis(d,lambda o,g:g['err4_condi'][i][k]==1,300,5000)
        print('slope',sl,'eapp',ea,nm,'on: 5min %.4f raw %.4f corr %.4f glu %.3f | off: 5min %.4f raw %.4f glu %.3f'%(g1['tran_inA_5min'][i],g1['tran_inA'][i].max(),g1['out_iir'][i],o1['result_glucose'][i],g2['tran_inA_5min'][i],g2['tran_inA'][i].max(),o2['result_glucose'][i]))
