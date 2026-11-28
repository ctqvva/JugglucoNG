from stress import *
np.set_printoptions(linewidth=250,precision=3,suppress=True)
seq,t,x,T,o,g=run('low60')
for i in range(499,515): print(i,g['err4_condi'][i][0],g['err4_delay_condi'][i],'raw<0.3',(g['tran_inA'][i]<0.3).sum(),'raw min/max %.3f %.3f'%(g['tran_inA'][i].min(),g['tran_inA'][i].max()), g['tran_inA'][i][::5])
