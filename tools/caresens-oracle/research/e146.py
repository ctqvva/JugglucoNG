from gen import *
np.set_printoptions(linewidth=230,precision=4,suppress=True)
N=1600; w=mkwo(N,1,80,250); w[1000:1300]=2800
r,o,g,a,s=run(dev(),w,34.,snap=True)
x=g['tran_inA_5min']
for k in list(range(995,1012))+[1100,1299,1300,1301,1310,1400]:
    print(k+1,'cur %.3f'%x[k],'min %.4f range %.4f md %.4f'%(g['err4_min'][k],g['err4_range'][k],g['err4_min_diff'][k]),'c',g['err4_condi'][k],'dc',g['err4_delay_condi'][k],g['err4_delay_flag'][k],'e',o['errcode'][k],'dt',o['data_type'][k])
print('inA tail',s['err4_inA'][1005][-8:], 'minprev',s['err4_min_prev'][1005][-4:],'rangeprev',s['err4_range_prev'][1005][-4:])
print('5min',g['tran_inA_5min'][1000:1006],'1min last',g['tran_inA_1min'][1003:1006,-1])
