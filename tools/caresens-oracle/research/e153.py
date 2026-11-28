from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=5,suppress=True)
seq,t,adc,T,c,o,g,s,d=runspec(mf.HOLDOUT['holdout-gaps-spikes'],True)
k=kt(d,adc.astype('u2'),T,seq,t)
for q in (1100,1103,1104,1105,1106,1110,1219,1220):
    print(q+1,'seq',seq[q],'lvl lib',s['diabetes_level_diabetes'][q],'mine',k['lvl'][q],'cnt',s['diabetes_cnt_idx'][q],'mdc cnt',s['sum_curr_cnt_for_mdc'][q],'mdc start',s['mdc_start_idx'][q],s['mdc_first_flag'][q],'TAR',s['diabetes_TAR'][q],'corr lib',g['out_iir'][q],'mine',k['corr'][q],'x',g['corrected_re_current'][q])
print('====')
x=g['corrected_re_current']; y=s['iir_y']; X=s['iir_x']
acc=(o['data_type']!=2)
hy=0;hx=None;isum=0;icnt=0;first=None
for q in range(len(seq)):
    if not acc[q]: continue
    sq_=seq[q]
    if 5<=sq_<=24: isum+=x[q];icnt+=1
    if sq_>=24 and icnt:
        if hx is None: hx=isum/icnt
        hy=.99891*(x[q]-hx)+.99782*hy; hx=x[q]
    if abs(hy-y[q])>1e-9 and first is None: first=q; print('first HP diff at',q+1,'seq',sq_,'prev seq',seq[q-1],seq[q-2],'mine',hy,'lib',y[q],'libX',X[q],'x',x[q],'icnt',icnt, 'lib initcnt',s['sum_curr_cnt_for_init_value'][q])
