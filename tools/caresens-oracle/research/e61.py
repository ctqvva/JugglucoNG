from gen import *
np.set_printoptions(linewidth=220,precision=5,suppress=True)
N=301; wo=mkwo(800,1,60,200); r,o,g,a=run(dev(),wo[:N],34.)
def st(a): 
    r,o,g,b=run(dev(),wo[N:N+1],34.,seq=[N+1],args=a.copy()); return o,g,b
o,g,b=st(a); print(a['smooth_time_in']-BASE, a['smooth_f_rep_in'],b['smooth_f_rep_in'],o['smooth_fixed_flag'])
base=b['smooth_sig_in'][0]
for fld in('smooth_sig_in','cal_result_input','cal_result_output','cal_result_in_smooth_slope'):
    J=[]
    for i in range(a[fld].shape[1]):
        q=a.copy(); q[fld][0][i]+=1e-3; J.append((st(q)[2]['smooth_sig_in'][0]-base)/1e-3)
    J=np.array(J); print(fld); print(J*21 if fld=='smooth_sig_in' else J)
