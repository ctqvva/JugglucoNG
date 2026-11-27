from sc import *
seq,t,adc,T,c,o,g,s,d=runspec(mf.HOLDOUT['holdout-gaps-spikes'],True)
x=g['corrected_re_current']; y=s['iir_y']; X=s['iir_x']
print(seq[:4], y[:4], X[:4], x[:4], s['iir_start_flag'][:4], s['sum_curr_cnt_for_init_value'][:4])
