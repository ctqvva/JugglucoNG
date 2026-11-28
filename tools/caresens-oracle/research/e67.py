from gen import *
np.set_printoptions(linewidth=220,precision=5,suppress=True)
def solo(w,seq=1): r,o,g,a=run(dev(),np.array(w).astype('u2')[None],34.,seq=[seq]); return int(o['data_type'][0])
def step(jj,hi,lo): w=np.full(30,hi); w[jj:]=lo; return w
def ctx(w,prev,n=40): 
    W=np.full((n+1,30),prev); W[n]=w; r,o,g,a=run(dev(),W.astype('u2'),34.); return int(o['data_type'][n])
print('solo',[(jj,solo(step(jj,14000,10000))) for jj in range(0,8)])
print('ctx flat prev 14000',[(jj,ctx(step(jj,14000,10000),14000)) for jj in range(0,10)])
print('ctx flat prev 10000',[(jj,ctx(step(jj,14000,10000),10000)) for jj in range(0,10)])
print('seq solo',[(s,solo(step(2,14000,10000),s)) for s in (1,5,10,11,12,13,20,24,25,100,1000)])
print('n',[(n,ctx(step(2,14000,10000),14000,n)) for n in (1,2,5,9,10,11,12,13,20)])
for jj in (1,2,3,4): print(jj,'min drop',[ [lo for lo in range(13990,1000,-10) if ctx(step(jj,14000,lo),14000,30)==2][:1] for _ in (0,)], [lo for lo in range(13990,1000,-100) if ctx(step(jj,14000,lo),14000,30)==2][-1:])
print('up',[(hi,ctx(step(2,10000,hi),10000,30)) for hi in (11000,12000,14000,20000,40000,60000)])
