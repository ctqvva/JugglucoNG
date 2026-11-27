from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=6,suppress=True)
spec={'seed': 314568128, 'readings': 4320, 'start': 600, 'slope100': 3.8899313057906912, 'vref': 1.455129972423152, 'eapp': 0.056893109748987106, 'lo': 60, 'hi': 210, 'knot_min': 30, 'noise': 5, 'temp_mode': 'step', 'temp_lo': 3073, 'decay': 0.7, 'gap_permille': 10, 'gap_max': 12, 'jitter': 30, 'spike_permille': 0, 'clock_jump_at': -1, 'clock_jump': 2000, 'knot_max': 60, 'temp_hi': 3073}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
q=1339
for z in (q-2,q-1,q,q+1):
    print(z+1,'seq',seq[z],'corr lib',g['out_iir'][z],'mine',k['corr'][z],'x',g['corrected_re_current'][z],'minute diff',np.abs(k['one'][z]-g['tran_inA_1min'][z]).max(),'lvl',s['diabetes_level_diabetes'][z],k['lvl'][z],'dcnt',s['diabetes_cnt_idx'][z])
z=q
print('lib hi',s['diabetes_cnt_hi_glu'][z],'lo',s['diabetes_cnt_low_glu'][z],'n',s['diabetes_cnt_idx'][z],'TAR',s['diabetes_TAR'][z],'TBR',s['diabetes_TBR'][z],'CV',s['diabetes_CV'][z],'mean',s['diabetes_mean_x'][z], 100*s['diabetes_cnt_hi_glu'][z]/s['diabetes_cnt_idx'][z])
