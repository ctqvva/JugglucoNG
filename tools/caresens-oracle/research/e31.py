from e28 import *
def near(q): return np.sort(D,axis=1)[:,q-1][:,None]
def Ev2(K,its,c=6):
    e=[]
    for y,t in DS:
        dl=np.ones(90)
        for it in range(its+1):
            f=fit(y,K*dl); dl=mk(np.median,c)(y-f,it,dl)
        e.append((pred(f)-t)[1:]**2)
    return np.sqrt(np.mean(e))
for its in (1,2,3,4): print(its,[(q,round(Ev2(tri(near(q)),its)*1e6,1)) for q in (85,86,87,88,89)],[(k,round(Ev2(tri(hm-k),its)*1e6,1)) for k in (2,3,4)])
