from gen import *
rng=np.random.default_rng(0); N=400; sq=np.arange(1,N+1); t=BASE+300*sq+rng.integers(-40,41,N)
r,o,g,a,s=run(dev(),mkwo(N,1),34.,seq=sq,t=t,snap=True)
d=o['measurement_time_standard'].astype(np.int64)-t; print('out time - in',np.unique(d)[:10], 'S times - in',np.unique(s['smooth_time_in'][:,9].astype(np.int64)-t)[:5])
print('tsa',(s['time_standard_arr'][100][-5:].astype(np.int64)-BASE), (t[96:101]-BASE))
