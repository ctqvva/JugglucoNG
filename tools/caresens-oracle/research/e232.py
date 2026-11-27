from stress import *
np.set_printoptions(linewidth=220,precision=4,suppress=True)
seq,t,x,T,o,g=run('low60')
for i in range(499,515): print(i,g['err4_condi'][i][0],'1min',g['tran_inA_1min'][i],'5min %.4f'%g['tran_inA_5min'][i],'iir %.4f'%g['out_iir'][i],'corr %.4f'%g['corrected_re_current'][i])
