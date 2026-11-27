from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=5,suppress=True)
spec={'seed': 161973070, 'readings': 300, 'start': 600, 'slope100': 2.8607466875847867, 'vref': 1.5578293725584356, 'eapp': 0.046777363019657146, 'lo': 60, 'hi': 410, 'knot_min': 3, 'noise': 100, 'temp_mode': 'const', 'temp_lo': 3019, 'decay': 1, 'gap_permille': 40, 'gap_max': 40, 'jitter': 0, 'spike_permille': 0, 'clock_jump_at': -1, 'clock_jump': 40000, 'knot_max': 6, 'temp_hi': 3019}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
for q in range(0,4): print(q+1,seq[q],'lib g',o['result_glucose'][q],'mine',k['g'][q],'cg',g['init_cg'][q],k['cg'][q],'res',g['out_rescale'][q],k['res'][q],'corr',g['out_iir'][q],k['corr'][q],'one',g['tran_inA_1min'][q])
