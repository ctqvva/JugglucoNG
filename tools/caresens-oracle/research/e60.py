from gen import *
np.set_printoptions(linewidth=220,precision=6,suppress=True)
N=800; wo=mkwo(N,1,60,200); r,o,g,a,s=run(dev(),wo,34.,snap=True)
t=o['trendrate'];res=o['result_glucose']
print('uncomputed',(t==100).sum(),'first computed',np.argmax(t!=100)+1, 'dbg==out',np.array_equal(t,g['trendrate']))
v=np.nonzero(t!=100)[0]; print(v[:10]+1,t[v[:10]])
for nm,x in(('res',res),('smooth5',None),('resc',g['out_rescale']),('init',g['init_cg'])):
    if x is None: continue
    for w in range(2,14):
        sl=np.array([np.polyfit(np.arange(w)*5,x[i-w+1:i+1],1)[0] if i>=w else 0 for i in range(N)]); e=np.abs(sl-t)[v]; print(nm,w,round(float(np.median(e)),5),end=' ')
    print()
for i in (300,301): print(i+1,'t',t[i],'res',res[i-11:i+1],'smooth',o['smooth_result_glucose'][i],'ssig',g['smooth_sig'][i],g['smooth_seq'][i],'sin',s['smooth_sig_in'][i],s['cal_result_input'][i],s['cal_result_output'][i])
