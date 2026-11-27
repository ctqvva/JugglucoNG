exec(open('e211.py').read().split("for j in range")[0])
def P(st): return runf(st)[0]
b2=b.copy(); b2['err_glu_arr'][0][:]=0.0
print('flat0',runf(b2))
for D in (1000,3000,10000):
    row=[]
    for j in range(287,274,-1):
        st=b2.copy(); st['err_glu_arr'][0][j]=D; row.append(P(st))
    print(D,row)
for k in (0,20,50,100):
    st=b2.copy(); st['out_kalman'][0][:]+=k; print('kal',k,runf(st))
