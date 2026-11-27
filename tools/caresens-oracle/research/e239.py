import craft
from craft import *
for sl in (7.0,):
    d=co.default_device(); d['slope100'][0]=sl; craft.DEV=d
    prev=None
    for c in np.round(np.arange(-0.5,3.2,0.05),3):
        o,g,s=run_plateau(float(c),12)
        key=(g['err4_condi'][508].tolist(),g['err4_delay_condi'][508].tolist(),int(o['data_type'][508]))
        if key!=prev: print('slope',sl,'c %5.2f 5min %.4f glu %.2f corr %.4f'%(c,g['tran_inA_5min'][508],o['result_glucose'][508],g['corrected_re_current'][508]),key)
        prev=key
