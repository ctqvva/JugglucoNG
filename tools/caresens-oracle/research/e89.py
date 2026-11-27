from ref import *
from gen import mkwo
np.set_printoptions(linewidth=200,precision=4,suppress=True)
wo=mkwo(1500,1,noise=600); r,o,g,a,s=run(dev(),wo[:20],34.,snap=True)
for i in range(7,15): print(i+1,'z',g['out_rescale'][i],'K',s['out_kalman'][i],'roc',s['kalman_roc'][i],'st',s['state_init_kalman'][i],s['kalman_state_fluctuation'][i],'w',s['warmup_cnt'][i],'ad',g['opcal_ad'][i])
# interpolation fill
import o as O
N0=40; d=dev()
def multi(W,w,T,t,tg,n0=N0,sg=1):
    sq=np.arange(1,n0+1); r,o,g,a=run(d,W,T)
    i=np.zeros(1,INP); i['seq_number']=n0+sg; i['measurement_time_standard']=BASE+300*n0+tg; i['workout']=w; i['temperature']=t
    out=np.zeros(6*155,'u1'); dbg=np.zeros(6*1579,'u1'); c=np.zeros(1,CAL)
    res=L.oracle_step(H,p(d),p(i),p(c),p(a),p(out),p(dbg))&255
    return res,out.view(OUT),dbg.view(DBG),a
rng=np.random.default_rng(2)
W=rng.integers(9000,9100,(N0,30)); w=rng.integers(9500,9600,30)
for tg in (600,620,900,1300):
    res,oo,gg,a=multi(W,w,34.,35.,tg); print(tg,res)
    for k in range(res-1):
        v=oo['workout'][k].astype(int); print('  t',oo['measurement_time_standard'][k]-BASE-300*N0,'T',gg['temperature'][k],'data',v[:6],v[-3:], 'mean',v.mean())
    print('  last t',oo['measurement_time_standard'][res-1]-BASE-300*N0, 'time_prev',a['time_prev']-BASE-300*N0,a['seq_prev'],a['idx_origin_seq'],a['cumul_sum'])
print(W[-1][:6],W[-1][-3:],w[:6],w[-3:])
