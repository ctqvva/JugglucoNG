from sc import *
np.set_printoptions(linewidth=230,precision=5,suppress=True)
spec=dict(seed=5,readings=1200,gap_permille=30,gap_max=5,spike_permille=10,jitter=20)
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
acc=np.cumsum(o['data_type']!=2)
for q in (0,5,500,1100):
    print(q,'seq',seq[q],'idx',s['idx'][q],'accepted',acc[q],'args_seq',s['args_seq'][q],'seq_prev',s['seq_prev'][q],'time_prev-t',int(s['time_prev'][q])-int(t[q]),'shiftout_prev',s['shiftout_ad_prev'][q],'G',o['result_glucose'][q],'accu tail',s['accu_seq'][q][-3:],'Sseq',o['smooth_seq'][q][-2:],'contact',s['contact_err_start_seq'][q],s['prev_contact_err_algo_seq'][q],'t10',(s['time_10sec_arr'][q][[0,59,60,89]].astype(np.int64)-int(t[q])))
r=run1=None
# idx saturation
spec2=dict(seed=6,readings=1500); seq2,t2,adc2,T2,c2,o2,g2,s2,d2=runspec(spec2,True); print('idx at 864..',s2['idx'][[863,864,865,866,1400]],'accu',s2['accu_seq'][1400][-3:])
