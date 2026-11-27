from lab import *
np.set_printoptions(linewidth=190,precision=4,suppress=True)

d=dev(); u=float(d['vref'][0])/40950*100; e=float(d['eapp'][0])*100
A=lambda x:(x+e)/u
rng=np.random.default_rng(5)
w0=rng.normal(10000,20,30).astype('u2'); w1=rng.normal(10000,20,30).astype('u2')
def two(w1,w0=w0,seqs=(1,2),a0=None):
    r,o,g,a,s=run(d,np.array([w0,w1]),34.0,seq=list(seqs),snap=True); return A(s['prev_outlier_removed_curr'][1]),A(g['tran_inA_1min'][1]),s['outlier_max_index'][1],A(g['tran_inA'][1]),A(s['prev_mov_median_curr'][1]),A(s['prev_outlier_removed_curr'][0])
