exec(open('e208.py').read().split("print('base',runf(base))")[0])
b=base.copy(); b['err_glu_arr'][0][:]=150.0
p0=runf(b); print('flat150',p0)
for j in range(287,250,-1):
    st=b.copy(); st['err_glu_arr'][0][j]=150+240
    p=runf(st); 
    if p[0]!=p0[0] or p[1]!=p0[1]: print(j-287,'P',p[0],'ISF',p[1], 'dP*',(p[0]-p0[0])/240)
