from ref import *
from gen import mkwo
np.set_printoptions(linewidth=200,precision=4,suppress=True)
wo=mkwo(60,1)
def rp(seq,t=None,wo=wo[:50],rows=range(36,50)):
    seq=np.array(seq); t=BASE+300*seq if t is None else t
    r,o,g,a,s=run(dev(),wo,34.,seq=seq,t=t,snap=True); z=g['out_rescale']
    for i in rows: 
        dt=int(t[i])-int(t[i-1]); print(seq[i],'dt',dt,'roc',s['kalman_roc'][i],'(dz/5)',(z[i]-z[i-1])/5,'(dz/dtmin)',(z[i]-z[i-1])/dt*60 if dt else 0,'icg',s['init_cg_prev'][i]-z[i],'w',s['warmup_cnt'][i])
sq=np.arange(1,51); sq[40:]+=2; rp(sq,rows=range(39,42))
sq=np.arange(1,51); sq[40:]+=8; rp(sq,rows=range(38,46))
sq=np.arange(1,51); t=BASE+300*sq; t[40:]+=7; rp(sq,t,rows=range(39,42))
t=BASE+300*sq; t[40:]-=300; rp(sq,t,rows=range(39,43))
rp(sq,rows=range(6,14))
