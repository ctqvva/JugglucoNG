from e20 import *
np.set_printoptions(linewidth=230,precision=4,suppress=True)
acc=np.load('kern.npy')
# effective weight of last 8 rows: compare with hat matrix averaged for col 4 (i in 84..89 mm/tm)
x=np.arange(90.)
def hat(kern):
    H=np.zeros((90,90))
    for i in range(90):
        w=kern(np.abs(x-x[i]),np.abs(x-x[i]).max()); V=np.vander(x-x[i],2,increasing=True); H[i]=(np.linalg.pinv(V*np.sqrt(w)[:,None])*np.sqrt(w))[0]
    return H
def proj(H): # rows for 5 minutes: tm(mm(linear)) -> x=2.25+6k index approx
    out=[]
    for k in range(5):
        b=60+6*k; out.append((H[b+1]+2*H[b+2]+ 0*H[b+3])/3*0+(H[b+2]+H[b+3])/2)
    out.append((H[86]+H[87])/2);out.append(H[87]);out.append((H[87]+H[88])/2)
    return np.array(out).T[:60]
for nm,k in dict(tri=lambda d,h:np.clip(1-(d/h)**3,0,1)**3,tri90=lambda d,h:np.clip(1-(d/90)**3,0,1)**3,tri110=lambda d,h:np.clip(1-(d/(h*1.1))**3,0,1)**3,bi=lambda d,h:np.clip(1-(d/h)**2,0,1)**2,ep=lambda d,h:np.clip(1-(d/h)**2,0,1), gau=lambda d,h:np.exp(-.5*(d/h*2.5)**2)).items():
    P=proj(hat(k)); print(nm,np.abs(P-acc)[10:].max(0)); 
P=proj(hat(lambda d,h:np.clip(1-(d/h)**3,0,1)**3)); print(P[::4,4]);print(acc[::4,4])
print(P[::4,0]);print(acc[::4,0])
