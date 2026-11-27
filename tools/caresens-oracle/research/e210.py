exec(open('e200.py').read().split("f=[n")[0])
E=s['err_glu_arr']; P=g['err16_CGM_plasma']
m=np.nonzero(~np.isnan(P))[0]; m=m[m>300]
res=[]
for N in range(2,40):
    W=E[m][:,-N:]
    for nm,fn in (('median',lambda w:np.median(w,1)),('mean',lambda w:w.mean(1)),('nanmedian',lambda w:np.nanmedian(w,1))):
        v=fn(W)
        for rn,r in (('round',np.round),('floor',np.floor),('ceil',np.ceil),('half',lambda x:np.floor(x+.5))):
            res.append((np.mean(r(v)==P[m]),N,nm,rn))
for x in sorted(res,reverse=True)[:6]: print(x)
