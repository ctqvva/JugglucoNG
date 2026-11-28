from sc import *
import differential as df
jar=df.engine_jar()
spec=dict(seed=9,readings=1000,start=1)
seq0,t0,adc,T=[np.array(x) for x in scenarios.generate(**spec)]
d=co.default_device(); full=dict(scenarios.DEFAULTS,**spec); P=(full['slope100'],full['vref'],full['eapp'])
def trial(events,jump_at,jump,dts=1100):
    seq=seq0.copy(); t=t0.copy().astype(np.int64)
    for e in events: t[e:]+=dts
    if jump_at is not None: seq[jump_at:]+=jump; t[jump_at:]+=jump*300
    recs=co.records(seq,t,np.array(adc,'u2'),T)
    c,o,g,a,s=REF.run(d,recs,snapshots=True)
    k=df.run_engine(jar,P,recs)
    ve=np.nonzero(o[:,0]['errcode']&32)[0]; ke=np.nonzero(k['error']&32)[0]
    return (ve[0] if len(ve) else None),(ke[0] if len(ke) else None), s['err32_buff_23'][-1].tolist(), s['err32_n'][-1].tolist()
print('no jump  ', trial([50,400,700,900],None,0))
print('seq jump ', trial([50,400,700,900],450,300))
print('seq jump2', trial([50,400,700,905],450,300))
print('idx span>864 seq',trial([20,400,700,890],None,0))
