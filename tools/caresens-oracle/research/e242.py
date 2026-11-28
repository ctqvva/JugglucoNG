from stress import *
def check(name,N=4,dev=None):
    seq,t,x,T,o,g=run(name,dev=dev); d=co.default_device() if dev is None else dev
    sl=float(d['slope100'][0]); raw=g['tran_inA']; C=g['err4_condi'][:,0]
    pred=np.zeros(len(C),bool)
    for i in range(N-1,len(C)):
        m=raw[i-N+1:i+1].max(); pred[i]= m<0.5 and m*100/sl<10
    bad=np.nonzero(pred!=C.astype(bool))[0]
    return len(bad),bad[:8].tolist(),C.sum()
for nm in ('low60','low30','zero','step_down','slow_drop','saw'):
    print(nm,[check(nm,N) for N in (3,4,5)])
