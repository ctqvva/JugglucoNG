from lab import *
np.set_printoptions(linewidth=200,precision=8,suppress=True)
rng=np.random.default_rng(1)
N=60
wo=(10000+2000*np.sin(np.arange(N)/8))[:,None]+rng.normal(0,30,(N,30)); wo=wo.astype('u2')
d=dev(); r,o,g,a=run(d,wo,34.0)
for k in ['stage','temperature','tran_inA_5min','ycept','corrected_re_current','out_iir','out_drift','curr_baseline','initstable_diff_dc','initstable_initcnt','temp_local_mean','slope_ratio_temp','init_cg','out_rescale','opcal_ad','state_init_kalman','cal_state','state_return_opcal','out_weight_ad','shiftout_ad','trendrate','cal_available_flag','data_type','nOpcalState','initstable_weight_usercal','initstable_weight_nocal','initstable_fixusercal','initstable_init_end_point','error_code1','error_code2','error_code4','error_code8','error_code16','error_code32']:
    print(k,g[k][[0,1,2,3,5,10,20,22,23,24,25,30,40,50,59]])
for k in ['result_glucose','current_stage','errcode','cal_available_flag','seq_number_original','seq_number_final','smooth_seq','smooth_result_glucose','smooth_fixed_flag','data_type']: print(k,o[k][[0,1,2,3,10,23,24,25,40,59]])
print(r)
print(g['tran_inA'][0],g['tran_inA_1min'][0],wo[0])
