from e27 import *
K=tri(hm)
def Ev(rws,K=K,its=2,fin=None): 
    e=[]
    for y,t in DS:
        dl=np.ones(90)
        for it in range(its+1):
            f=fit(y,K*dl); dl=rws(y-f,it,dl)
        e.append((pred(f)-t)**2)
    return np.sqrt(np.mean(e))
def mk(m,c=6,p=2): return lambda r,it,dl:np.clip(1-(r/(c*m(abs(r))))**2,0,1)**p
for n,m in dict(med=np.median,lo=lambda a:np.sort(a)[44],hi=lambda a:np.sort(a)[45],med60=lambda a:np.median(a[:60]),med30=lambda a:np.median(a[60:]),).items(): print(n,Ev(mk(m)))
print('cum',Ev(lambda r,it,dl:dl*mk(np.median)(r,it,dl)))
print('c variants',[ (c,Ev(mk(np.median,c))) for c in (5.8,5.9,5.95,6.05,6.1)])
print('K sq variants',Ev(mk(np.median),K=tri(hm)**.5),Ev(mk(np.median),K=tri(hm)**2))
for h in (87,88,89,89.5,90,91): print(h,Ev(mk(np.median),K=tri(np.maximum(hm,0)*h/89)))
# residual vs first
y,t=DS[0]; 
for y,t in DS[:4]:
  dl=np.ones(90)
  for it in range(3): f=fit(y,K*dl); dl=mk(np.median)(y-f,it,dl)
  print((pred(f)-t)*1e5)
