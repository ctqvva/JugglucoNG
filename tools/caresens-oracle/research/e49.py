from gen import *
np.set_printoptions(linewidth=220,precision=6,suppress=True)
N=600; wo=mkwo(4320,1)[:N]; r,o,g,a,s=run(dev(),wo,34.,snap=True)
for i in (3,4,5,15,16,17,18,22,23,24,25,26,27,36,60):
    print(i+1,'iirout %.6f corr %.6f x'%(g['out_iir'][i],g['corrected_re_current'][i]),s['iir_x'][i],'y',s['iir_y'][i],'start',s['iir_start_flag'][i],'use',s['iir_use_flag'][i],'sum',s['sum_curr_for_init_value'][i],s['sum_curr_cnt_for_init_value'][i],'mdc',s['sum_curr_for_mdc'][i],s['sum_curr_cnt_for_mdc'][i],s['mdc_start_idx'][i],s['mdc_first_flag'][i],'temp',s['slope_ratio_temp_buffer'][i],'bias',s['biastrend'][i],s['biasIIR'][i],s['biasavg'][i],s['nSumtrend'][i])
# which args fields change over time (non err)
chg=[f for f in ARGS.names if not f.startswith('err') and np.any(s[f][100]!=s[f][300])]; print(chg)
