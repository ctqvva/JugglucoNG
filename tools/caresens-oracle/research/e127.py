from e126 import go,np,BASE,Counter
print('#####')
sq=np.arange(1,900)
def cls(D):
    t=BASE+300*sq; t[500:]+=D; e,g,s,o=go(sq,t); return (int(s['err32_n'][520][0]),int(s['err32_n'][520][1]),int(s['err32_n'][520][2]))
def edge(lo,hi):
    a=cls(lo)
    while hi-lo>1:
        m=(lo+hi)//2
        if cls(m)==a: lo=m
        else: hi=m
    return lo,hi,cls(lo),cls(hi)
print(edge(300,1500),edge(1500,10000),edge(10000,86400))
# windows: multiple gaps of D spaced K
def multi(D,K,n,N=1500):
    sq=np.arange(1,N); t=BASE+300*sq
    for j in range(n): t[300+j*K:]+=D
    e,g,s,o=go(sq,t); nz=np.nonzero(e)[0]; return (nz[0]+1,nz[-1]+1,len(nz)) if len(nz) else None
for D in (1500,4000):
    for K in (10,100,200,250,280,288,289,300,500):
        print('D',D,'K',K,[multi(D,K,n) for n in (2,3,4,5)])
