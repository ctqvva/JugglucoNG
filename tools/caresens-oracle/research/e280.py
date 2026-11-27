from sc import *
import json
from cart import grow
cs=json.load(open('c0specs.json')); sv=json.load(open('err_survey.json'))
X=[];Y=[]
names=['rmax','prev_rmax','5min','prev_5min','R','prevR','init','seq','idx','err4','prev_type','rmean','min1max','D0','D3']
for sp in cs+[x['spec'] for x in sv[:60]]:
    seq,t,adc,T,c,o,g,s,d=runspec(sp,True)
    acc=o['data_type']!=2; ai=np.nonzero(acc)[0]
    RW=g['tran_inA'].max(1)
    for a,b in zip(ai[:-1],ai[1:]):
        if not (865<=seq[b]<2017): continue
        if RW[b]>4: continue
        X.append([RW[b],RW[a] if seq[a]==seq[b]-1 else 99,g['tran_inA_5min'][b],g['tran_inA_5min'][a],o['result_glucose'][b],o['result_glucose'][a],g['init_cg'][b],seq[b],s['idx'][b],(o['errcode'][a]&4)>0,o['data_type'][a],g['tran_inA'][b].mean(),g['tran_inA_1min'][b].max(),g['err4_delay_condi'][b][0],g['err4_delay_condi'][b][3]])
        Y.append(g['err4_delay_condi'][b][1])
X=np.array(X,float); Y=np.array(Y,float); print(X.shape,Y.sum())
np.save('d1X.npy',X); np.save('d1Y.npy',Y)
grow(X,Y,names,maxd=5,minleaf=3)
