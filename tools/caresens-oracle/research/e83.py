from ref import *
from gen import mkwo
np.set_printoptions(linewidth=200,precision=6,suppress=True)
wo=mkwo(400,1,noise=300); r,o,g,a,s=run(dev(),wo,34.,snap=True)
K=s['out_kalman'][:,1]; b=np.nonzero(np.abs(K-g['opcal_ad'])[40:]>1e-9)[0]+40; print(len(b),b[:10]+1)
for i in b[:6]: print(i+1,'res',g['out_rescale'][i],'cg',g['init_cg'][i],'ad',g['opcal_ad'][i],'K',s['out_kalman'][i],'dt',o['data_type'][i],'prev ad',g['opcal_ad'][i-1],'wcnt',s['warmup_cnt'][i],s['shiftout_ad_prev'][i],g['shiftout_ad'][i],o['result_glucose'][i])
# extremes
for lev in (-100,0,10,20,24,25,26,30,490,500,510,900):
    r,o,g,a=run(dev(),mkwo(60,1,level=lev),34.); print(lev,g['opcal_ad'][[1,15,59]],o['result_glucose'][[1,15,59]],o['data_type'][[1,15,59]],o['errcode'][[1,15,59]],o['trendrate'][59],o['smooth_result_glucose'][59][:2],a['cal_result_output'][0][-2:],a['out_kalman'][0])
