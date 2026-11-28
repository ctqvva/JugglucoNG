from craft import *
seq,t,adc,T=base(); n=200
for a0 in (0,1,2,5,20):
    a=adc[:n].copy(); a[a0:a0+10]=adc_for(0.2)
    cc,o,g,_,s=REF.run(DEV,co.records(seq[:n],t[:n],a.astype('u2'),T[:n]),snapshots=True); o=o[:,0]; g=g[:,0]
    print('low from record',a0,'C0 first',np.nonzero(g['err4_condi'][:,0])[0][:1].tolist(),'err4 first',np.nonzero(o['errcode']&4)[0][:1].tolist(),'D first',[np.nonzero(g['err4_delay_condi'][:,k])[0][:1].tolist() for k in range(5)],'types',o['data_type'][a0:a0+5].tolist())
# rejected record after latch
a=adc[:n].copy(); a[50:60]=adc_for(0.2); a[80,0]=a[80,0]*6+3000; a[80,1]=a[80,1]*2
cc,o,g,_,s=REF.run(DEV,co.records(seq[:n],t[:n],a.astype('u2'),T[:n]),snapshots=True); o=o[:,0]
print('rejected record 80 type',o['data_type'][80],'errcode',o['errcode'][80],'neighbors',o['errcode'][79],o['errcode'][81])
