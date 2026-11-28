exec(open('e200.py').read().split("f=[n")[0])
I=g['err16_CGM_ISF_smooth']; m=np.nonzero(~np.isnan(I))[0]
for nm in ('init_cg','out_rescale','out_drift','out_iir','corrected_re_current','tran_inA_5min','curr_baseline','opcal_ad','shiftout_ad','out_weight_sd','out_weight_ad'):
    x=g[nm][m].astype(float)
    if x.ndim>1: continue
    ok=np.isfinite(x)
    A=np.stack([x[ok],np.ones(ok.sum())],1); c,*_=np.linalg.lstsq(A,I[m][ok],rcond=None); r=I[m][ok]-A@c
    print('%-22s coef %s resid sd %.3f'%(nm,np.round(c,4),r.std()))
print(g['tran_inA_5min'].shape)
