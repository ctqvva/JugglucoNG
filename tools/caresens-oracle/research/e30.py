from lab import *
np.set_printoptions(linewidth=230,precision=6,suppress=True)
w=np.full((4,30),10000,'u2'); d=dev(); r,o,g2,a,s2=run(d,w,34.0,snap=True); st=s2[2:3].copy(); base=g2['tran_inA_1min'][3][0]
def f(n):
    q=st.copy(); q['prev_outlier_removed_curr'][0]=base+n
    r,o,g,a=run(d,w[3:],34.0,seq=[4],args=q); return np.append(g['tran_inA_1min'][0],a['prev_mov_median_curr'][0])-base
x=np.arange(90.); D=np.abs(x[:,None]-x[None]); hm=D.max(1)[:,None]
def hatrow(w,i): 
    u=x-x[i]; S0,S1,S2=w.sum(),(w*u).sum(),(w*u*u).sum(); return w*(S2-S1*u)/(S0*S2-S1*S1)
res={}
for lo in (20,30,40,50):
    n=np.zeros(60); n[:10]=1.*np.array([1,-1]*5); n[10:60]=np.array([50,-50,-50,50,50]*10)*np.array([1,-1]*25); n[10:60]=[60,-60]*25; 
    inl=np.arange(lo,lo+10); n[inl]=0
    mask=np.ones(90); mask[10:60]=0; mask[inl]=1
    for j in list(inl[::3]):
        m=n.copy(); m[j]=1e-4; A_=f(m); m[j]=-1e-4; res[j]=(A_-f(m))/2e-4
    print(lo,f(n)[[0,4]])
    for j in inl[::3]:
        row=[]
        for h in (89,88,87,86,85):
            K=np.clip(1-(D/(hm*h/89))**3,0,1)**3; row.append((hatrow(K[87]*mask,87))[j])
        print(j,'lib med at 87:',res[j][6],'models h',np.array(row))
print('---')
from scipy.optimize import minimize_scalar,minimize
obs=[]
for lo in (20,30,40,50):
    n=np.zeros(60); n[:10]=1.*np.array([1,-1]*5); n[10:60]=[60,-60]*25; inl=np.arange(lo,lo+10); n[inl]=0
    mask=np.ones(90); mask[10:60]=0; mask[inl]=1
    for j in inl[1::4]:
        m=n.copy(); m[j]=1e-4; A_=f(m); m[j]=-1e-4; obs.append((mask,j,(A_-f(m))/2e-4))
def wt(k): # minute k combos
    b=60+6*k; return {b+1:.125,b+2:.5,b+3:.375}
for k in range(5):
    def E(p):
        h,a,b=p; e=0
        for mask,j,v in obs:
            pr=sum(c*hatrow(np.clip(1-(D[i]/(h+ (hm[i,0]-hm[60+6*k+2,0])))**a,0,1)**b*mask,i)[j] for i,c in wt(k).items()); e+=(pr-v[k])**2
        return e*1e6
    r=minimize(E,[hm[60+6*k+2,0]-3,3,3],method='Nelder-Mead',options=dict(maxiter=800,xatol=1e-6,fatol=1e-12)); print(k,62.25+6*k,r.x,r.fun, 'fixed33',min((E([h,3,3]),h) for h in np.arange(50,92,.25)))
