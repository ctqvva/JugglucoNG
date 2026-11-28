exec(open('e200.py').read().split("f=[n")[0])
P=g['err16_CGM_plasma']; S=o['smooth_result_glucose']; R=o['result_glucose']
m=np.nonzero(~np.isnan(P))[0]; m=m[(m>20)]
res=[]
for lag in range(0,8):
    for k in range(6):
        v=S[m-lag,k]
        for fn,name in ((np.round,'round'),(np.floor,'floor'),(np.ceil,'ceil'),(lambda x:np.floor(x+0.5),'half')):
            res.append((np.mean(fn(v)==P[m]),'S[i-%d][%d] %s'%(lag,k,name)))
    for nm in ('result_glucose',):
        res.append((np.mean(np.round(R[m-lag])==P[m]),'R lag %d'%lag))
    for nm in ('init_cg','out_rescale','out_drift'):
        res.append((np.mean(np.round(g[nm][m-lag])==P[m]),nm+' lag %d'%lag))
for r in sorted(res,reverse=True)[:8]: print(r)
