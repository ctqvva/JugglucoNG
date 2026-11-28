from ref import *
from gen import mkwo
np.seterr(all='ignore')
rng=np.random.default_rng(0)
for k in range(2):
    N=1500; sq=np.cumsum(rng.choice([1]*30+[2,3,5,6,7,40],N)); t=BASE+300*sq+rng.integers(-[0,3,20,40][k],[0,3,20,40][k]+1,N); w=mkwo(N,k,noise=[12,50,100,30][k])
r,o,g,a,s=run(dev(),w,34.,seq=sq,t=t,snap=True); R=Ref(dev()); n=0
for i in range(N):
    m=R.step(int(sq[i]),int(t[i]),w[i],34.)
    if abs(m['tr']-o['trendrate'][i])>1e-6 and n<6:
        n+=1; S=R.S; T=R.St; print(sq[i],'lib',o['trendrate'][i],'mine',m['tr'],'d9',S[9]-S[8],'d8',S[8]-S[7],'m',(S[9]-S[3])/((T[9]-T[3])/60),'T',np.diff(T),'Sq',R.Sq[3:])
print('====')
np.set_printoptions(linewidth=220,precision=3,suppress=True)
Rr=Ref(dev())
for i in range(N):
    m=Rr.step(int(sq[i]),int(t[i]),w[i],34.)
    if sq[i] in (552,553,554,555,556):
        old=s['smooth_sig_in'][i-1]; new=s['smooth_sig_in'][i]; b=np.append(old[1:],o['result_glucose'][i])
        print(sq[i],'lib moved',np.nonzero(np.abs(b-new)>1e-9)[0],'lib-mine S',(new-Rr.S),'Tlib',np.diff(s['smooth_time_in'][i].astype(np.int64)), s['smooth_f_rep_in'][i])
