from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=4,suppress=True)
seq,t,adc,T,c,o,g,s,d=runspec(mf.HOLDOUT['holdout-gaps-spikes'],True)
k=kt(d,adc.astype('u2'),T,seq,t)
diff=np.abs(k['g']-o['result_glucose']); diff[o['data_type']==2]=0
cgd=np.abs(k['cg']-g['init_cg']); cgd[o['data_type']==2]=0
i=np.argmax(cgd>1e-7); j=np.argmax(diff>1e-7); print('first cg diff',i+1,'first glu diff',j+1)
for q in range(i-3,i+2): print(q+1,'seq',seq[q],'dt',t[q]-t[q-1],'dtype',o['data_type'][q],'cnt',c[q],'cg',k['cg'][q],g['init_cg'][q],'one',g['tran_inA_1min'][q])
print('====')
for q in range(j-3,j+1): print(q+1,'seq',seq[q],'dt',t[q]-t[q-1],'dtype',o['data_type'][q],'res',g['out_rescale'][q],'ad',g['opcal_ad'][q],'mine',k['g'][q],'K',s['out_kalman'][q],'roc',s['kalman_roc'][q],'w',s['warmup_cnt'][q],'st',s['state_init_kalman'][q],s['kalman_state_fluctuation'][q])
