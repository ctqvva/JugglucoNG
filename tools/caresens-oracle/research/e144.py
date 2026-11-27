from gen import *
rng=np.random.default_rng(0); N=800; sq=np.cumsum(rng.choice([1]*20+[2,3,7],N)); t=BASE+300*sq+rng.integers(-140,141,N)
r,o,g,a,s=run(dev(),mkwo(N,1),34.,seq=sq,t=t,snap=True)
bad=0
for i in range(20,N):
    true=np.array([0]*0)
    Q=o['smooth_seq'][i]; T=s['smooth_time_in'][i][3:9].astype(np.int64)
    # actual seqs of the S entries = seqs of last 10 readings
    act=sq[i-6:i][:]  # S[3..8] are readings i-6..i-1
    if not np.array_equal(Q,act): bad+=1; 
    if bad and bad<4 and not np.array_equal(Q,act): print(sq[i],Q,act,T-BASE, o['seq_number_final'][i-6:i])
print('mismatch',bad, 'any data_type',np.unique(o['data_type']))
print('idx',s['idx'][[10,100,799]],'args_seq',s['args_seq'][[10,100,799]],'accu',s['accu_seq'][799][-8:],sq[-8:], 'seqfinal',o['seq_number_final'][-3:])
