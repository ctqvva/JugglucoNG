exec(open('e200.py').read().split("f=[n")[0])
P=g['err16_CGM_plasma']; R=o['result_glucose'].astype(float)
m=np.nonzero(~np.isnan(P))[0]; m=m[(m>20)]
cons=np.array([np.all(np.diff(seq[i-12:i+1])==1) for i in m]); m=m[cons]
for K in (6,8,10,12):
    X=np.stack([R[m-k] for k in range(K)]+[np.ones(len(m))],1)
    c,*_=np.linalg.lstsq(X,P[m],rcond=None); r=P[m]-X@c
    print(K,np.round(c*21,3),'resid sd',r.std(),'max',abs(r).max())
