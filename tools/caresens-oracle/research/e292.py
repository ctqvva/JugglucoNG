from stress import *
from cart import grow
f=[n for n in co.DEBUG.names if n.startswith('err1') and co.DEBUG[n].shape==() and 'noise_temp_break' not in n and 'result' not in n and 'TD' not in n]
X=[];Y=[]
for nm in ('noise200','noise1000','alt','spike1','ramp_in_rec','dropouts','halfrec','saw'):
    seq,t,x,T,o,g=run(nm)
    raw=g['tran_inA']
    for i in range(1,len(seq)):
        X.append([g[n][i] for n in f]+[raw[i].std(),np.abs(np.diff(raw[i])).mean(),raw[i].max()-raw[i].min(),g['tran_inA_5min'][i]])
        Y.append(g['err1_random_noise_temp_break'][i])
X=np.nan_to_num(np.array(X,float),nan=-1e9); Y=np.array(Y,float)
names=[n[5:] for n in f]+['raw_sd','raw_absdiff','raw_range','5min']
print(X.shape,Y.sum())
grow(X,Y,names,maxd=4,minleaf=5)
