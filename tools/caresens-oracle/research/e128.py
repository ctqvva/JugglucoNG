from e127 import multi,go,np,BASE
print('@@@@@')
for K in (600,864,865,1000,1500,2000):
    print(K,multi(4000,K,2,N=K+400))
print('23min with 4 gaps spanning 864 exactly offset', multi(1500,288,4,N=1400), multi(1380,288,4,N=1400), multi(1379,288,4,N=1400))
# combination: 1 gap 60 + 3 gaps 23?
sq=np.arange(1,1500); t=BASE+300*sq; t[300:]+=4000; t[400:]+=1500; t[500:]+=1500; e,g,s,o=go(sq,t); nz=np.nonzero(e)[0]; print('mix',nz[:1]+1,s['err32_n'][600])
sq=np.arange(1,1500); t=BASE+300*sq; t[300:]+=4000; t[400:]+=1500; t[500:]+=1500; t[600:]+=1500; e,g,s,o=go(sq,t); nz=np.nonzero(e)[0]; print('mix4',nz[:1]+1,s['err32_n'][700])
# seq gap also counts? (seq jumps with consistent time)
sq=np.arange(1,1500); sq[300:]+=12; sq[400:]+=12; e,g,s,o=go(sq); nz=np.nonzero(e)[0]; print('seq gaps 60min x2',nz[:1]+1, s['err32_n'][600])
