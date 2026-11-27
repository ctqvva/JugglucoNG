from gen import *
np.set_printoptions(linewidth=200,precision=6,suppress=True)
N=4320; dec=np.linspace(1,.9,N)[:,None]; w=mkwo(N,2,80,250); W=(2756+(w-2756.)*dec).clip(0,65535).astype('u2')
r,o,g,a,s=run(dev(),W,34.,snap=True)
x=g['corrected_re_current'];y=s['iir_y'];lp=x-y;dl=g['out_iir']-x; ref=.95*s['sum_curr_for_mdc'][2100]/1152; mdc=ref/.95
for k in range(2165,2185): print(k+1,'lp/mdc %.5f'%(lp[k]/mdc),'dl %.6f'%dl[k],'pred %.6f'%(min(1,(k+1-2016)/288)*max(0,ref-lp[k])),'flags',s['iir_use_flag'][k],s['iir_start_flag'][k],s['mdc_first_flag'][k], 'base/mdc %.4f'%(g['curr_baseline'][k]/mdc))
nz=np.nonzero(np.abs(dl)>0)[0]; print('dl nz ranges',nz[:3]+1,nz[-3:]+1,len(nz))
print('====')
b=g['curr_baseline']
for nm,v in (('od',g['out_drift']),('x',x),('iir',g['out_iir']),('init',g['init_cg'])):
    c=np.cumsum(v); cm=c/np.arange(1,N+1); print(nm,'cummean err',np.abs(cm-b).max(), [np.abs(np.array([v[max(0,i-W_+1):i+1].mean() for i in range(0,N,7)])-b[::7]).max() for W_ in (288,576,864,1152)])
print('acc',s['accu_seq'][3000][:5],s['accu_seq'][3000][-5:],s['idx'][3000],'curr_avg_arr',s['curr_avg_arr'][3000][:3],s['curr_avg_arr'][3000][-3:], 'od',g['out_drift'][2997:3000])
