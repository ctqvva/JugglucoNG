from e28 import *
print('hm-k',[(k,round(Ev(mk(np.median),K=tri(hm-k))*1e6)) for k in range(0,10)])
print('hm*f',[(f,round(Ev(mk(np.median),K=tri(hm*f))*1e6)) for f in (.9,.92,.94,.95,.96,.97,.98,.99)])
def near(q): 
    return np.sort(D,axis=1)[:,q-1][:,None]
for its in (1,2,3):print('q',its,[(q,round(Ev(mk(np.median),K=tri(near(q)),its=its)*1e6)) for q in range(70,91,2)])
