# usage: python3 dg.py "<spec dict>" first_record_1based
import sys, ast
from sc import *
import differential as df
spec=ast.literal_eval(sys.argv[1]); at=int(sys.argv[2])-1
seq,t,adc,T=scenarios.generate(**spec); full=dict(scenarios.DEFAULTS,**spec); d=co.default_device()
for k in ('slope100','vref','eapp'): d[k][0]=full[k]
recs=co.records(seq,t,np.array(adc,'u2'),T)
c,o,g,_,_=REF.run(d,recs); o=o[:,0]; g=g[:,0]
k=df.run_engine(df.engine_jar(),(full['slope100'],full['vref'],full['eapp']),recs)
seq=np.array(seq)
for i in range(max(0,at-5),at+3):
    print(i+1,'seq',seq[i],'V err',o['errcode'][i],'type',o['data_type'][i],'D',g['err4_delay_condi'][i].tolist(),'C',g['err4_condi'][i].tolist(),'noise',g['err1_random_noise_temp_break'][i],
          '| K err',k['error'][i],'type',k['type'][i],'ex',k['exact'][i],'| raw max %.3f 5min %.3f R %.2f/%.2f'%(g['tran_inA'][i].max(),g['tran_inA_5min'][i],o['result_glucose'][i],k['glucose'][i]))
