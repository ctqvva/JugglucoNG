exec(open('e213.py').read().split("print('flat0'")[0])
from fractions import Fraction as F
for D in (1e12,1e14):
    w=[]
    for j in range(287,276,-1):
        st=b2.copy(); st['err_glu_arr'][0][j]=D; w.append((P(st)-17)/D)
    print(D,repr(w), 'newest', 1-sum(w))
