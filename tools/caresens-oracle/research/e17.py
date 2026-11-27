from e16 import *
rng=np.random.default_rng(6); d=dev()
N=3000
for kind in range(4): wo=10000+rng.normal(0,[5,30,300,30,2,1][kind],(N,30))+(np.arange(N*30).reshape(N,30)%997*4 if kind==3 else 0)
r,o,g,a,s=run(d,wo.astype('u2'),34.0,snap=True)
for i in (431,432,433,1429,1430):
    print(i,'x',g['tran_inA'][i][:8],'q',s['prev_outlier_removed_curr'][i][30:38]);print('   end x',g['tran_inA'][i][-6:],'q',s['prev_outlier_removed_curr'][i][-6:],s['outlier_max_index'][i],s['prev_current'][i],s['prev_new_i_sig'][i], g['err1_result'][i], o['errcode'][i],o['data_type'][i])
    print('   prev30==',np.abs(s['prev_outlier_removed_curr'][i][:30]-s['prev_outlier_removed_curr'][i-1][30:]).max())
