from gen import *
np.set_printoptions(linewidth=220,precision=6,suppress=True)
N=4320; T=34+1.5*np.sin(np.arange(N)/37)
for ea,vr,sl in((.10067,1.49594,3.5226),(.10067,1.45,5.0),(.05,1.49594,3.5226),(.06,1.5,4.2)):
    d=dev(eapp=ea,vref=vr,slope100=sl); wo=mkwo(N,1,T=T,d=d); r,o,g,a=run(d,wo,T)
    print('----',ea,vr,sl,'err',np.unique(o['errcode'],return_counts=True))
    y=g['ycept']; print('ycept off',np.ptp(y-g['tran_inA_5min']),(y-g['tran_inA_5min'])[0],'corr==ycept',np.abs(g['corrected_re_current']-y).max())
    print('iir==corr',np.abs(g['out_iir']-g['corrected_re_current']).max(),'n diff',(np.abs(g['out_iir']-g['corrected_re_current'])>1e-9).sum(), 'first',np.argmax(np.abs(g['out_iir']-g['corrected_re_current'])>1e-9))
    ra=g['out_drift']/g['out_iir']; print('drift ratio',ra[[0,1,2,3,10,50,100,287,288,289,300,500,1000,2000,3000,4000,4319]])
    print('rescale==init',np.abs(g['out_rescale']-g['init_cg']).max())
    k=g['init_cg']/(g['out_drift']*100/float(d['slope100'][0])/g['slope_ratio_temp']); print('initcg k',k.min(),k.max())
    print('templm-temp',g['temp_local_mean'][:6],T[:6])
    print('wt==ad',np.abs(g['out_weight_ad']-g['opcal_ad']).max(),np.abs(g['shiftout_ad']-g['opcal_ad']).max(),np.abs(o['result_glucose']-g['opcal_ad']).max())
