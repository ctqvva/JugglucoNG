from gen import *
np.set_printoptions(linewidth=230,precision=5,suppress=True)
N=4320; S0=3000
w=mkwo(N,1,level=150).astype(float); w[S0-1-40:]=2756+(w[S0-1-40:]-2756)*.4; W=w.clip(0,65535).astype('u2')
r,o,g,a,s=run(dev(),W,34.,snap=True)
x=g['corrected_re_current'];dl=g['out_iir']-x
for k in list(range(2955,2966))+[2980,3000,3100,3500]:
    print(k+1,'x %.4f dl %.5f'%(x[k],dl[k]),'e4',g['error_code4'][k],'min %.4f range %.4f mdiff %.4f'%(g['err4_min'][k],g['err4_range'][k],g['err4_min_diff'][k]),g['err4_condi'][k],g['err4_delay_condi'][k],g['err4_delay_flag'][k],'ec',o['errcode'][k],'e16',g['error_code16'][k])
print('e4 state', s['err4_result_prev'][2960], s['err4_inA'][2960][-5:], s['err4_min_prev'][2960][-3:],s['err4_range_prev'][2960][-3:],s['err4_min_diff_prev'][2960][-3:])
