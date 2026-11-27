from gen import *
np.set_printoptions(linewidth=220,precision=5,suppress=True)
rng=np.random.default_rng(6); d=dev(); N=3000
for kind in range(4): wo=10000+rng.normal(0,[5,30,300,30,2,1][kind],(N,30))+(np.arange(N*30).reshape(N,30)%997*4 if kind==3 else 0)
wo=wo.astype('u2'); r,o,g,a=run(d,wo,34.)
i=np.nonzero(o['data_type'])[0]; print(i, o['data_type'][i], r[i], o['errcode'][i], o['seq_number_final'][i],o['result_glucose'][i])
for k in i[:3]: print(wo[k-1][-3:],wo[k][:4],wo[k][-3:], o['seq_number_original'][k],o['result_glucose'][k-1],o['result_glucose'][k+1],g['opcal_ad'][k], o['smooth_seq'][k],o['smooth_result_glucose'][k],o['trendrate'][k],o['current_stage'][k])
ii=np.nonzero(o['errcode'])[0]; print(ii[:20],o['errcode'][ii][:20])
# what if jump at j
def tst(jj,hi,lo,n0=40):
    W=np.full((n0+3,30),hi); W[n0,jj:]=lo; W[n0+1:]=lo; W=(W+np.random.default_rng(0).normal(0,5,W.shape)).astype('u2'); r,o,g,a=run(d,W,34.); return int(o['data_type'][n0]),int(o['data_type'][n0+1]),int(o['errcode'][n0]),round(float(g['tran_inA_5min'][n0]),3)
for jj in (0,1,2,5): print(jj,[ (hi,tst(jj,hi,10000)) for hi in (12000,14000,16000,20000)])
