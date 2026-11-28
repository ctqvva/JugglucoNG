exec(open('e213.py').read().split("print('flat0'")[0])
from fractions import Fraction as F
D=1e8; w=[]
for j in range(287,276,-1):
    st=b2.copy(); st['err_glu_arr'][0][j]=D; w.append((P(st)-17)/D)
print('w old (slot0..-10)',w,'sum',sum(w))
r0=runf(b2)
for k in (1e5,1e6):
    st=b2.copy(); st['out_kalman'][0][:]+=k; r=runf(st); print('new weight',(r[0]-r0[0])/(r[2]-r0[2]), r)
print([F(x).limit_denominator(2000) for x in w])
