exec(open('e208.py').read().split("print('base',runf(base))")[0])
r0=runf(base); print('base',r0)
for nm in ['curr_avg_arr','err1_SG_1min','err1_inA_1min','err16_CGM_ISF_smooth','err16_CGM_plasma','err_glu_arr','prev_outlier_removed_curr','smooth_sig_in','err16_dt_arr','err4_inA']:
    for sl in (slice(-1,None),slice(-6,None),slice(None)):
        st=base.copy(); st[nm][0][sl]=st[nm][0][sl]*1.3+5
        r=runf(st)
        if r[1]!=r0[1]: print(nm,sl,'ISF',r[1],'P',r[0])
