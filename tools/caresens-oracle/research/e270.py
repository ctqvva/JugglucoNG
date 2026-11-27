from craft import *
seq,t,adc,T=base(); n=120
s2=seq[:n].copy(); t2=t[:n].copy(); a=adc[:n].copy()
s2[100:]+=2; t2[100:]+=600
a[103,0]=a[103,0]*6+3000; a[103,1]=a[103,1]*2
cc,o,g,_,s=REF.run(DEV,co.records(s2,t2,a.astype('u2'),T[:n]),snapshots=True); o=o[:,0]; g=g[:,0]
for i in (99,100,101,103,104):
    A=s['err4_inA'][i].reshape(13,30)
    print(i,'seq',s2[i],'type',o['data_type'][i],'rows max (newest last)',np.round(np.nanmax(np.where(np.isnan(A),-9,A),1)[-6:],3),'nan rows',np.isnan(A).all(1)[-6:].astype(int), 'this raw max %.3f'%g['tran_inA'][i].max())
