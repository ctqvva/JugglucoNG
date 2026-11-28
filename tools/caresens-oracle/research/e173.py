from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=5,suppress=True)
spec={'seed': 741781775, 'readings': 4320, 'start': 1500, 'slope100': 5.731055222740928, 'vref': 1.5763077742835625, 'eapp': 0.08936027540883351, 'lo': 70, 'hi': 150, 'knot_min': 60, 'noise': 12, 'temp_mode': 'const', 'temp_lo': 3049, 'decay': 0.7, 'gap_permille': 40, 'gap_max': 40, 'jitter': 45, 'spike_permille': 0, 'clock_jump_at': -1, 'clock_jump': 40000, 'knot_max': 120, 'temp_hi': 3149}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
q=274
for z in (0,1,q-2,q-1,q,q+1):
    print(z+1,'seq',seq[z],'corr lib',g['out_iir'][z],'mine',k['corr'][z],'x',g['corrected_re_current'][z],'mdc',s['sum_curr_cnt_for_mdc'][z],s['mdc_start_idx'][z],s['mdc_first_flag'][z],'lvl',s['diabetes_level_diabetes'][z],k['lvl'][z],'dcnt',s['diabetes_cnt_idx'][z],'iir',s['iir_start_flag'][z],s['iir_y'][z],'init',s['sum_curr_cnt_for_init_value'][z])
