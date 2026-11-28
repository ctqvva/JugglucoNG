from gen import *
np.set_printoptions(linewidth=200,precision=5,suppress=True)
N=4320
for lo,hi in ((30,250),(60,80)):
    w=mkwo(N,3,lo,hi,noise=10).astype(float); w[2600:]=2756+(w[2600:]-2756)*.4; W=w.clip(0,65535).astype('u2')
    r,o,g,a,s=run(dev(),W,34.,snap=True)
    x=g['corrected_re_current'];y=s['iir_y'];lp=x-y;dl=g['out_iir']-x; mdc=s['sum_curr_for_mdc'][2100]/1152
    act=np.nonzero(np.abs(dl)>0)[0]; print(lo,hi,'n act',len(act),act[:5]+1,act[-3:]+1)
    sq=np.arange(1,N+1); w_=np.minimum(1,(sq-2016)/288.)
    for k in act[::max(1,len(act)//10)]: print(k+1,'dl %.5f lp/mdc %.5f (mdc-lp)w %.5f ratio %.4f'%(dl[k],lp[k]/mdc,(mdc-lp[k])*w_[k],dl[k]/((mdc-lp[k])*w_[k])),o['errcode'][k])
