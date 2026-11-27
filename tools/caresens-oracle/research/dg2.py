import sys, ast
from sc import *
import differential as df
spec=ast.literal_eval(sys.argv[1]); at=int(sys.argv[2])-1
seq,t,adc,T=scenarios.generate(**spec); full=dict(scenarios.DEFAULTS,**spec); d=co.default_device()
for k in ('slope100','vref','eapp'): d[k][0]=full[k]
recs=co.records(seq,t,np.array(adc,'u2'),T)
c,o,g,_,s=REF.run(d,recs,snapshots=True); o=o[:,0]; g=g[:,0]
k=df.run_engine(df.engine_jar(),(full['slope100'],full['vref'],full['eapp']),recs)
seq=np.array(seq)
for i in range(max(0,at-4),at+3):
    print(i+1,'seq',seq[i],'type',o['data_type'][i],'err',o['errcode'][i],'V corr %.6f K %.6f | V x(corr_re) %.6f 5min %.4f K minutes0 %.4f'%(g['out_iir'][i],k['current'][i],g['corrected_re_current'][i],g['tran_inA_5min'][i],k['minutes'][i][0]),'iir_y %.5f'%s['iir_y'][i],'ref n',s['cnt_curr_for_mdc'][i] if 'cnt_curr_for_mdc' in s.dtype.names else '')
