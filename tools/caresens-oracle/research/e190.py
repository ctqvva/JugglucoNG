from sc import *
spec=dict(seed=9,readings=400,start=1)
seq,t,adc,T=[np.array(x) for x in scenarios.generate(**spec)]
t=t.astype(np.int64); adc=adc.copy()
t[300:]+=1100
adc[300][0]=int(adc[300][0]*1.6); adc[300][1]=int(adc[300][1]*1.05)
d=co.default_device()
c,o,g,a,s=REF.run(d,co.records(seq,t,np.array(adc,'u2'),T),snapshots=True)
for i in (299,300,301): print(i,'type',o[i,0]['data_type'],'idx',s['idx'][i],'buff23',s['err32_buff_23'][i].tolist(),'prev_seq',s['err32_prev_seq'][i])
