from sc import *
for spec in (dict(seed=3,readings=600,start=1,slope100=4.0,temp_mode='const',temp_lo=3400,temp_hi=3400,lo=60,hi=300),
             dict(seed=3,readings=600,start=1,slope100=6.0,temp_mode='const',temp_lo=3600,temp_hi=3600,lo=60,hi=300),
             dict(seed=3,readings=600,start=1,slope100=4.0,eapp=0.06,temp_mode='const',temp_lo=3400,temp_hi=3400,lo=60,hi=300)):
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    I=g['err16_CGM_ISF_smooth']; m=np.nonzero(~np.isnan(I))[0]
    for nm in ('tran_inA_5min','corrected_re_current','init_cg','out_drift'):
        x=g[nm][m].astype(float); A=np.stack([x,np.ones(len(m))],1); cc,*_=np.linalg.lstsq(A,I[m],rcond=None)
        print(spec['slope100'],spec.get('eapp'),nm,np.round(cc,4),'sd',np.std(I[m]-A@cc).round(3))
