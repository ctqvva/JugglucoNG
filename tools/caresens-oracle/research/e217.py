exec(open('e200.py').read().split("f=[n")[0])
I=g['err16_CGM_ISF_smooth']; m=np.nonzero(~np.isnan(I))[0]
sl=float(d['slope100'][0]); rt=g['slope_ratio_temp']
cands={}
for nm in ('tran_inA_5min','corrected_re_current','out_iir','out_drift'):
    x=g[nm].astype(float)
    cands[nm+'*100/s']=x*100/sl
    cands[nm+'*100/s/rt']=x*100/sl/rt
    cands[nm+'*100/s*rt']=x*100/sl*rt
for k,v in cands.items():
    for rn,r in (('round',np.round),('floor',np.floor)):
        print('%-34s %s match %.3f  resid sd %.3f'%(k,rn,np.mean(r(v[m])==I[m]),np.std(I[m]-v[m])))
print('rt sample',rt[2000:2003], 'cur',g['corrected_re_current'][2000:2003],'5min',g['tran_inA_5min'][2000:2003],'I',I[2000:2003])
