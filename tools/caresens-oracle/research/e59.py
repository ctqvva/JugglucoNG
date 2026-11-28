from e56 import *
print('=====')
import o as O
def multi(sg,tg,n0=40,wo=wo):
    sq=np.arange(1,n0+1); r,o,g,a=run(dev(),wo[:n0],34.)
    i=np.zeros(1,INP); i['seq_number']=n0+sg; i['measurement_time_standard']=BASE+300*n0+tg; i['workout']=wo[n0]; i['temperature']=35.
    out=np.zeros(6*155,'u1')+0xAA; dbg=np.zeros(6*1579,'u1')+0xAA; c=np.zeros(1,CAL); d=dev()
    res=L.oracle_step(H,p(d),p(i),p(c),p(a),p(out),p(dbg))&255
    oo=out.view(OUT);gg=dbg.view(DBG)
    touched=[int((out[155*k:155*k+155]!=0xAA).sum()) for k in range(6)]
    return res,touched,oo,gg,a
for tg in (600,900,1200):
    res,t,oo,gg,a=multi(1,tg); print(tg,res,t)
    for k in range(res): print('  ',k,oo['seq_number_original'][k],oo['seq_number_final'][k],oo['measurement_time_standard'][k]-BASE,oo['data_type'][k],oo['errcode'][k],oo['result_glucose'][k],oo['workout'][k][:4],gg['temperature'][k],gg['tran_inA_5min'][k],gg['opcal_ad'][k])
print(wo[39][:4],wo[40][:4]); print(a['idx_origin_seq'],a['seq_prev'],a['cumul_sum'],a['args_seq'])
