from e4 import *
# look at structure for random data in different scales
for sc in (20,300,3000):
    rng=np.random.default_rng(3)
    w=(10000+rng.normal(0,sc,30)).astype('u2')
    b=one(w); J=np.array([one(w+1*(np.arange(30)==i).astype('u2'))[1]-b[1] for i in range(30)])
    print(sc)
    for k in range(5):
        nz=[(i,round(float(J[i,k]),3),int(w[i])) for i in range(30) if abs(J[i,k])>1e-6]
        print(k,round(float(b[1][k]),4),nz)
    print(w.reshape(5,6)); print(b[0].round(2).reshape(5,6)); print(A(b[3]['prev_outlier_removed_curr'][0][30:]).round(2).reshape(5,6)); print(b[3]['outlier_max_index'],A(b[3]['prev_mov_median_curr']),A(b[3]['prev_current']),A(b[3]['prev_new_i_sig']))
