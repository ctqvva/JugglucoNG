import numpy as np, itertools
z=np.load('err16_ds.npz'); X=z['X']; Y=z['Y'].astype(bool); names=[n.replace('err16_CGM_ISF_','').replace('err16_','') for n in z['names']]
F=X.shape[1]
for k in range(3):
    y=Y[:,k]
    masks=[]
    for j in range(F):
        p=X[y,j]
        if np.isnan(p).any(): continue
        masks.append((j,'>=',p.min(),X[:,j]>=p.min())); masks.append((j,'<=',p.max(),X[:,j]<=p.max()))
    res=[]
    for a,b in itertools.combinations(range(len(masks)),2):
        if masks[a][0]==masks[b][0]: continue
        neg=(masks[a][3]&masks[b][3]&~y).sum(); res.append((neg,a,b))
    res.sort()
    print('cond',k,'pos',y.sum())
    for neg,a,b in res[:5]: print('   neg',neg, *[(names[masks[q][0]],masks[q][1],round(float(masks[q][2]),5)) for q in (a,b)])
