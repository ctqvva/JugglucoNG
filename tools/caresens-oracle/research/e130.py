from gen import *
P=[];Q=[]
for seed,(lo,hi) in enumerate(((-20,40),(0,30),(-50,20),(5,15),(0,60))):
    w=mkwo(1500,seed,lo,hi,noise=20); r,o,g,a,s=run(dev(),w,34.,snap=True)
    c=g['err8_condi']; G=o['result_glucose']; cur=g['tran_inA_5min']; cg=g['init_cg']; e8=g['error_code8']
    for k in range(1,1500):
        P.append((c[k,0],c[k,1],G[k],cur[k],cg[k],g['out_rescale'][k],e8[k],k+1,o['errcode'][k],o['data_type'][k], s['err8_result_prev'][k]))
P=np.array(P)
for j,nm in ((0,'c0'),(1,'c1')):
    on=P[P[:,j]==1];off=P[P[:,j]==0]
    for f,fn in ((2,'G'),(3,'cur'),(4,'cg'),(5,'res')): print(nm,fn,'on max',on[:,f].max() if len(on) else None,'off min',off[:,f].min(),'on min',on[:,f].min() if len(on) else None,'off max',off[:,f].max())
from collections import Counter; print(Counter([(int(a),int(b),int(c_)) for a,b,c_ in P[:,[0,1,6]]]))
print('dt4 vs G',P[P[:,9]==4][:,2].max(), P[P[:,9]==0][:,2].min())
