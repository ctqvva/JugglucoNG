from sc import *
BASE=dict(seed=21,readings=1500,start=1,lo=80,hi=200,knot_min=30,knot_max=120,noise=12)
def base():
    seq,t,adc,T=[np.array(x) for x in scenarios.generate(**BASE)]
    return seq,t.astype(np.int64),adc.astype(np.int64),T.astype(float)
rng=np.random.default_rng(1)
def tf(name,adc,a,b):
    x=adc.copy(); w=x[a:b]
    if name=='flat': x[a:b]=int(w.mean())
    elif name=='flat_rec': x[a:b]=w.mean(1,keepdims=True).astype(int)
    elif name=='low30': x[a:b]=(w*0.3).astype(int)
    elif name=='low60': x[a:b]=(w*0.6).astype(int)
    elif name=='high3': x[a:b]=np.minimum(w*3,65535)
    elif name=='zero': x[a:b]=0
    elif name=='sat': x[a:b]=4095*10
    elif name=='noise200': x[a:b]=w+rng.integers(-200,200,w.shape)
    elif name=='noise1000': x[a:b]=np.maximum(w+rng.integers(-1000,1000,w.shape),0)
    elif name=='alt': x[a:b]=(w*np.where(np.arange(b-a)%2,1.3,0.7)[:,None]).astype(int)
    elif name=='spike1': y=w.copy(); y[:,15]*=2; x[a:b]=y
    elif name=='ramp_in_rec': x[a:b]=(w*(0.8+0.4*np.arange(30)/29)[None,:]).astype(int)
    elif name=='step_down': x[a:]= (x[a:]*0.5).astype(int)
    elif name=='step_up': x[a:]= (x[a:]*1.8).astype(int)
    elif name=='slow_drop': f=np.linspace(1,0.3,b-a); x[a:b]=(w*f[:,None]).astype(int); x[b:]=(x[b:]*0.3).astype(int)
    elif name=='dropouts': y=w.copy(); y[::3]=0; x[a:b]=y
    elif name=='saw': x[a:b]=(w*(1+0.5*np.sin(np.arange(b-a)/2))[:,None]).astype(int)
    elif name=='halfrec': y=w.copy(); y[:,15:]=(y[:,15:]*0.5).astype(int); x[a:b]=y
    return np.clip(x,0,65535)
NAMES=['flat','flat_rec','low30','low60','high3','zero','sat','noise200','noise1000','alt','spike1','ramp_in_rec','step_down','step_up','slow_drop','dropouts','saw','halfrec']
def run(name,a=500,b=700,dev=None):
    seq,t,adc,T=base(); x=tf(name,adc,a,b)
    d=co.default_device() if dev is None else dev
    c,o,g,s,_=REF.run(d,co.records(seq,t,x.astype('u2'),T))
    return seq,t,x,T,o[:,0],g[:,0]
if __name__=='__main__':
    for nm in NAMES:
        seq,t,x,T,o,g=run(nm)
        e=o['errcode']; out={}
        for bit in (1,2,4,8,16,32,64,128):
            h=np.nonzero(e&bit)[0]
            if len(h): out[bit]=(int(h[0]),int(len(h)),int(h[-1]))
        print('%-12s'%nm,out,'types',np.unique(o['data_type'],return_counts=True))
