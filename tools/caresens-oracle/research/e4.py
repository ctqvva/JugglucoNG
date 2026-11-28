from lab import *
np.set_printoptions(linewidth=220,precision=4,suppress=True)
d=dev(); u=float(d['vref'][0])/40950*100; e=float(d['eapp'][0])*100
A=lambda x:(x+e)/u
def one(w,args=None,seq=1):
    r,o,g,a=run(d,w[None,:],34.0,seq=[seq],args=args); return A(g['tran_inA'][0]),A(g['tran_inA_1min'][0]),A(g['tran_inA_5min'][0]),a
if __name__=='__main__':
    w=(10000+np.arange(30)*100).astype('u2')
    b=one(w); print(b[1],b[2],A(b[3]['prev_mov_median_curr']))
    J=np.array([one(w+10*(np.arange(30)==i).astype('u2'))[1]-b[1] for i in range(30)])/10; print(J.T)
    rng=np.random.default_rng(3)
    w=np.cumsum(rng.integers(50,150,30)).astype('u2')+10000
    b=one(w); print(b[0]); print(b[1])
    J=np.array([one(w+4*(np.arange(30)==i).astype('u2'))[1]-b[1] for i in range(30)])/4; print(J.T)
