from e7 import *
q,m,oi,x,mmv,q0=two(w1)
for j in (4,8,10,20,26):
    jac=[]
    for i in range(60):
        a0=w0.copy();a1=w1.copy()
        (a0 if i<30 else a1)[i%30]+=1
        v=two(a1,a0)[0][30+j]-q[30+j]
        if abs(v)>1e-7: jac.append((i-30-j,round(v,3),int((w0 if i<30 else w1)[i%30])))
    print(j,w1[j],round(q[30+j],4),jac)
