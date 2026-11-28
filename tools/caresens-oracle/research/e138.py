from gen import *
rng=np.random.default_rng(0); N=400; sq=np.arange(1,N+1); t=BASE+300*sq+rng.integers(-40,41,N)
r,o,g,a,s=run(dev(),mkwo(N,1,60,300),34.,seq=sq,t=t,snap=True)
S=s['smooth_sig_in'];T=s['smooth_time_in'].astype(np.int64); tr=o['trendrate']
for i in range(20,40):
    if tr[i] in (0,100): continue
    den=(S[i,9]-S[i,3])/tr[i]*60
    print(i+1,'implied sec %.2f'%den,'T9-T3',T[i,9]-T[i,3],[T[i,9]-T[i,j] for j in range(10)])
