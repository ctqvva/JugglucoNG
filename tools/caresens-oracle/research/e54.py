from gen import *
np.set_printoptions(linewidth=220,precision=8,suppress=True)
N=600; wo=mkwo(N,1); r,o,g,a,s=run(dev(),wo,34.,snap=True)
for i in list(range(0,20))+[100,101,102,400,401,402]: print(i+1,'resc %.6f ad %.6f'%(g['out_rescale'][i],g['opcal_ad'][i]),'K',s['out_kalman'][i],'roc',s['kalman_roc'][i],s['kalman_state_fluctuation'][i],'w',s['warmup_cnt'][i],'st',s['state_init_kalman'][i],'icg',s['init_cg_prev'][i],'calin',s['cal_result_input'][i][-2:],'calout',s['cal_result_output'][i][-2:])
