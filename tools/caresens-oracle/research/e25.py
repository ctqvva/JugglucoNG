from e24 import *
K=tri(hm)
def med(r): return np.median(abs(r))
V={'bis1':lambda r,c:np.clip(1-(r/(c*med(r)))**2,0,1),
'bis_medc':lambda r,c:np.clip(1-((r)/(c*np.median(abs(r-np.median(r)))))**2,0,1)**2,
'tricube':lambda r,c:np.clip(1-np.abs(r/(c*med(r)))**3,0,1)**3,
'cauchy':lambda r,c:1/(1+(r/(c*med(r)))**2),'huber':lambda r,c:np.minimum(1,c*med(r)/np.maximum(abs(r),1e-12)),
'std':lambda r,c:np.clip(1-(r/(c*np.std(r)))**2,0,1)**2,'mean':lambda r,c:np.clip(1-(r/(c*np.mean(abs(r))))**2,0,1)**2,
'bis3':lambda r,c:np.clip(1-(r/(c*med(r)))**2,0,1)**3,'bis4':lambda r,c:np.clip(1-(r/(c*med(r)))**2,0,1)**4,
'q75':lambda r,c:np.clip(1-(r/(c*np.percentile(abs(r),75)))**2,0,1)**2}
def model2(y,K,c,its,rw): # cumulative
    dl=np.ones(90)
    for it in range(its+1):
        f=fit(y,K*dl); dl=dl*rw(y-f,c)
    return f
def e2(m,K,c,its,rw): return np.sqrt(np.mean([(np.array([tm(mm(q)) for q in m(y,K,c,its,rw)[60:].reshape(5,6)])-t)**2 for y,t in zip(Y,T)]))
best=[]
for n,rw in list(V.items())+[('bis',bis)]:
    for c in (1,1.5,2,2.5,3,3.5,4,4.685,5,6,7,8,9,10,12):
        for its in (1,2,3,5): best+= [(e2(model,K,c,its,rw),n,c,its,'r'),(e2(model2,K,c,its,rw),n,c,its,'cum')]
best.sort(); print(*best[:10],sep='\n')
# where's the error by minute?
for y,t in list(zip(Y,T))[:4]: print((np.array([tm(mm(q)) for q in model(y,K,6,2,bis)[60:].reshape(5,6)])-t)*1e4)
