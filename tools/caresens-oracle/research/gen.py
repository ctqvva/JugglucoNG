from lab import *
def mkwo(N,seed=0,lo=60,hi=250,noise=12,d=None,T=None,level=None):
    """realistic-ish: ADC for a smooth glucose curve, approx inverse of the factory chain"""
    rng=np.random.default_rng(seed); d=dev() if d is None else d
    n=N*30; kn=max(4,n//int(rng.uniform(500,1500)))
    g=np.interp(np.arange(n),np.linspace(0,n,kn),rng.uniform(lo,hi,kn)) if level is None else np.full(n,float(level))
    k=np.ones(61)/61; g=np.convolve(np.pad(g,30,mode='edge'),k,'valid')
    T=np.full(N,34.) if T is None else T
    cur=g*float(d['slope100'][0])/100*(1+0.0328*(np.repeat(T,30)-34.0854))+.7
    wo=(cur/100+float(d['eapp'][0]))*40950/float(d['vref'][0])+rng.normal(0,noise,n)
    return np.clip(wo,0,65000).reshape(N,30).astype('u2')
