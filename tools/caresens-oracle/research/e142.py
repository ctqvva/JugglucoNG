from gen import *
from collections import Counter
P=[]
for seed,(lo,hi) in enumerate(((-20,60),(0,60),(20,50),(30,45),(10,40))):
    w=mkwo(1500,seed,lo,hi,noise=12); r,o,g,a,s=run(dev(),w,34.,snap=True)
    for k in range(1500): P.append((o['data_type'][k],o['result_glucose'][k],o['errcode'][k],k+1,g['out_rescale'][k],g['init_cg'][k],g['tran_inA_5min'][k]))
P=np.array(P)
for e in np.unique(P[:,2]):
    q=P[P[:,2]==e]; d4=q[q[:,0]==4]; d0=q[q[:,0]==0]
    print('err',e,'n',len(q),'dt4 G range',(d4[:,1].min(),d4[:,1].max()) if len(d4) else None,'dt0 G range',(d0[:,1].min(),d0[:,1].max()) if len(d0) else None, 'dt4 cg range',(d4[:,5].min(),d4[:,5].max()) if len(d4) else None,'dt0 cg',(d0[:,5].min(),d0[:,5].max()) if len(d0) else None,'dt4 cur',(d4[:,6].min(),d4[:,6].max()) if len(d4) else None,'dt0 cur',(d0[:,6].min(),d0[:,6].max()) if len(d0) else None)
