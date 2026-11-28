from sc import *
spec=dict(seed=9,readings=1000,start=1)
seq0,t0,adc,T=[np.array(x) for x in scenarios.generate(**spec)]
def trial(dev,events,dts=1100):
    t=t0.copy().astype(np.int64)
    for e in events: t[e:]+=dts
    c,o,g,a,s=REF.run(dev,co.records(seq0,t,np.array(adc,'u2'),T))
    ve=np.nonzero(o[:,0]['errcode']&32)[0]; return ve[0] if len(ve) else None
for dt,n in (((23,60),(3,2)),((10,60),(3,2)),((23,60),(2,2)),((23,60),(5,2)),((30,60),(3,2))):
    d=co.default_device(); d['err32_dt'][0]=dt; d['err32_n'][0]=n
    print(dt,n,'4 gaps of 1400s at 50,400,700,900 ->',trial(d,[50,400,700,900]),' 3 gaps ->',trial(d,[50,400,700]),'gaps 700s x4 ->',trial(d,[50,400,700,900],dts=400))
