exec(open('e200.py').read().split("f=[n")[0])
P=g['err16_CGM_plasma']; I=g['err16_CGM_ISF_smooth']; S=o['smooth_result_glucose']
ok=~np.isnan(P); print('plasma=round(S1):',np.mean(np.round(S[ok,1])==P[ok]), 'consecutive seq only?')
bad=np.nonzero(ok&(np.round(S[:,1])!=P))[0]; print('bad',bad[:10], [ (P[b],S[b]) for b in bad[:3]])
# ISF: regress on plasma lags
m=np.nonzero(~np.isnan(I))[0]; m=m[m>40]
X=np.stack([P[m-k] for k in range(0,8)]+[np.ones(len(m))],1); good=~np.isnan(X).any(1)
c,*_=np.linalg.lstsq(X[good],I[m][good],rcond=None); print('lag coefs',np.round(c,4)); r=I[m][good]-X[good]@c; print('resid sd',r.std())
# maybe ISF from unrounded smooth
X2=np.stack([S[m,1]]+[np.ones(len(m))],1); c2,*_=np.linalg.lstsq(X2,I[m],rcond=None); print('vs S1',c2, (I[m]-X2@c2).std())
