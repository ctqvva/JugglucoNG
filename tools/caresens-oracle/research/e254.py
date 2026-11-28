exec(open('e251.py').read().split("for sl,ea in")[0])
base_adc=adc0.copy()
d=co.default_device()
for scale,start in ((1.0,300),(1.5,300),(0.8,300),(1.0,600),(1.0,100)):
    adc0=base_adc.copy(); adc0[:start]=(adc0[:start]*scale).astype(np.int64)
    def sim(av,dev,start=start):
        a=adc0.copy(); a[start:2500]=av
        cc,o,g,_,s=REF.run(dev,co.records(seq,t,a.astype('u2'),T),snapshots=True); return o[:,0],g[:,0]
    for nm,k in (('C2',2),('C3',3)):
        o1,g1,o2,g2=bis(d,lambda o,g:g['err4_condi'][1000][k]==1,300,5000)
        print('pre scale',scale,'plateau from',start,nm,'on 5min %.4f off %.4f'%(g1['tran_inA_5min'][1000],g2['tran_inA_5min'][1000]))
