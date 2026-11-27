exec(open('e211.py').read().split("for j in range")[0])
for j in (287,282,277):
    print(j-287,[ (D,runf((lambda st:(st['err_glu_arr'][0].__setitem__(j,150+D),st)[1])(b.copy()))[0]) for D in (-100,-60,-30,-12,12,30,60,120,240)])
# window length: newest value influence: set all to 150 then vary flat level
for L in (100,150,200,300):
    st=b.copy(); st['err_glu_arr'][0][:]=L; print('flat',L,runf(st)[0])
