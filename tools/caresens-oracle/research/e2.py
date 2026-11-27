from lab import *
np.set_printoptions(linewidth=170,precision=8,suppress=True)
rng=np.random.default_rng(1)
N=3
wo=(10000+2000*np.sin(np.arange(N)/8))[:,None]+rng.normal(0,30,(N,30)); wo=wo.astype('u2')
d=dev(); r,o,g,a,s=run(d,wo,34.0,snap=True)
for i in range(N):
    print(i); print(g['tran_inA'][i].reshape(5,6)); print((wo[i]*1.49594/40950-.10067).reshape(5,6)*100-g['tran_inA'][i].reshape(5,6)); print(g['tran_inA_1min'][i],g['tran_inA_5min'][i])
    for k in ['prev_outlier_removed_curr','outlier_max_index','prev_mov_median_curr','prev_current','prev_new_i_sig']: print(k,s[k][i])
