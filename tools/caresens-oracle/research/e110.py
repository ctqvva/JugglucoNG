from gen import *
np.set_printoptions(linewidth=200,precision=6,suppress=True)
N=4320; dec=np.linspace(1,.3,N)[:,None]; w=mkwo(N,1,80,250); W=(2756+(w-2756.)*dec).clip(0,65535).astype('u2')
r,o,g,a,s=run(dev(),W,34.,snap=True)
ra=g['out_drift']/g['corrected_re_current']; i0=2045
print(ra[i0:i0+12]); print(np.unique(np.round(ra[2051:],9))[:10], 'iir',np.abs(g['out_iir']-g['corrected_re_current'])[2040:].max())
for i in (2049,2050,2051,2052,2100,2500,3000,4000): print(i+1,'ratio',ra[i],'base',g['curr_baseline'][i],'cnt',s['sum_curr_cnt_for_mdc'][i],s['sum_curr_for_mdc'][i],s['mdc_start_idx'][i],s['mdc_first_flag'][i],'iir',s['iir_x'][i],s['iir_y'][i],s['iir_use_flag'][i],s['iir_start_flag'][i],'init',s['sum_curr_for_init_value'][i],g['out_iir'][i],g['corrected_re_current'][i], o['errcode'][i])
print('=====')
x=g['corrected_re_current']; y=s['iir_y'];X=s['iir_x']; init=s['sum_curr_for_init_value']/np.maximum(s['sum_curr_cnt_for_init_value'],1)
print('x arr is corr?',np.abs(X[30:,1]-x[30:]).max(),np.abs(X[30:,0]-x[29:-1]).max(), 'init',init[30])
i=np.arange(30,N)
A=np.c_[x[i],x[i-1],y[i-1],np.ones(len(i))]; c,_,_,_=np.linalg.lstsq(A,y[i],rcond=None); print('fit',c,np.abs(A@c-y[i]).max())
dd=g['out_iir']-x; print('delta vs y', np.c_[dd[[2060,2200,2600,3000,3500]],y[[2060,2200,2600,3000,3500]]])
print('#####')
lp=x-y; dl=g['out_iir']-x; mdc=s['sum_curr_for_mdc']/np.maximum(s['sum_curr_cnt_for_mdc'],1)
print('y fit c precise', np.polyfit(np.c_[y[i-1]].ravel(), (y[i]-0.99891*(x[i]-x[i-1])),1))
for k in list(range(2046,2056))+[2100,2300,2600,3000,3600,4300]: print(k+1,'lp %.5f mdc %.5f dl %.6f base %.5f'%(lp[k],mdc[k],dl[k],g['curr_baseline'][k]),'dl/(mdc-lp) %.6f'%(dl[k]/(mdc[k]-lp[k])),'od-x',g['out_drift'][k]-g['out_iir'][k], s['mdc_start_idx'][k],s['sum_curr_cnt_for_mdc'][k])
print('@@@@@')
T=.95*mdc-lp; k=np.arange(2051,2400)
for cols,nm in (((T[k],dl[k-1]),'T,dl-'),((T[k],dl[k-1],T[k-1]),'T,dl-,T-'),((T[k],dl[k-1],T[k-1],dl[k-2]),'+dl--'),((T[k],dl[k-1],dl[k-2]),'T dl- dl--'),((T[k],dl[k-1],y[k],y[k-1]),'T dl y')):
    A=np.c_[cols]; c,_,_,_=np.linalg.lstsq(A,dl[k],rcond=None); print(nm,c,np.abs(A@c-dl[k]).max())
print('&&&&&')
for start in ('T','0'):
    hp=np.zeros(N); out=np.zeros(N)
    for n in range(2050,N):
        if n==2050: hp[n]=T[n] if start=='T' else 0; continue
        hp[n]=.99891*(T[n]-T[n-1])+.99782*hp[n-1]
    pr=T-hp; print(start,np.c_[pr[[2050,2051,2052,2100,2300,3000]],dl[[2050,2051,2052,2100,2300,3000]]].T)
print('w',(dl/T)[2050:2060], 'ratio dl/T for dl', (dl[2051:2061]/dl[2050:2060]))
print('dlx',dl[2050:2058]*1e4,'T',T[2050:2058]*1e4)
print('%%%%%')
for nm,v in (('x',x),('lp',lp),('od',g['out_drift']),('iir',g['out_iir'])):
    print(nm,[v[a_:b_].sum() for a_,b_ in ((864,2016),(865,2017),(863,2015))],s['sum_curr_for_mdc'][2100])
print([(k+1,s['sum_curr_cnt_for_mdc'][k],s['mdc_start_idx'][k],s['mdc_first_flag'][k]) for k in (860,863,864,865,866,2014,2015,2016)])
print('w with seq', [(dl/T)[k]*288 - (k+1-2016) for k in (2050,2100,2200)])
