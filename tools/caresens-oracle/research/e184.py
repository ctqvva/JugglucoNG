from sc import *
import math
from fractions import Fraction as Fr
def fma(x,y,z): return float(Fr(x)*Fr(y)+Fr(z))
rows=[]
for sd,sl,vr,ea in ((1,3.1,1.43,0.05),(2,6.2,1.58,0.1),(3,4.4,1.5,0.08)):
    spec=dict(seed=sd,readings=150,start=1,slope100=sl,vref=vr,eapp=ea,noise=25,lo=40,hi=400)
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    V=float(d['vref'][0]); E=float(d['eapp'][0])
    for i in range(len(seq)):
        for j in range(5): rows.append((int(adc[i][25+j]),V,E,float(s['prev_current'][i][j])))
print(len(rows))
C={
 'eng':lambda a,V,E:(a*V/40950-E)*100,
 'fma(a*V,100/40950,-E*100)':lambda a,V,E:fma(a*V,100/40950,-E*100),
 'fma(a,V/409.5,-E*100)':lambda a,V,E:fma(a,V/409.5,-E*100),
 'fma(a*V,1/409.5,-E*100)':lambda a,V,E:fma(a*V,1/409.5,-E*100),
 'fma(a/40950,V,-E)*100':lambda a,V,E:fma(a/40950,V,-E)*100,
 'fma(a*V,1/40950,-E)*100':lambda a,V,E:fma(a*V,1/40950,-E)*100,
 'fma(a,V/40950,-E)*100':lambda a,V,E:fma(a,V/40950,-E)*100,
 'fma(a/40950,V*100,-E*100)':lambda a,V,E:fma(a/40950,V*100,-E*100),
 'fma(a,V*100/40950,-E*100)':lambda a,V,E:fma(a,V*100/40950,-E*100),
 'fma(a*V,10/4095,-E*100)':lambda a,V,E:fma(a*V,10/4095,-E*100),
 'fma(a/4095,V,-E*10)*10':lambda a,V,E:fma(a/4095,V,-E*10)*10,
 'fma(a,V/4095,-E*10)*10':lambda a,V,E:fma(a,V/4095,-E*10)*10,
 'fma(a*V,1/4095,-E*10)*10':lambda a,V,E:fma(a*V,1/4095,-E*10)*10,
 '(a*V*(1/40950)-E)*100':lambda a,V,E:(a*V*(1/40950)-E)*100,
 '(a*(V/40950)-E)*100':lambda a,V,E:(a*(V/40950)-E)*100,
 'fma(a*(V/4095),0.1,-E)*100':lambda a,V,E:fma(a*(V/4095),0.1,-E)*100,
 'fma(a/4095*V,0.1,-E)*100':lambda a,V,E:fma(a/4095*V,0.1,-E)*100,
 'fma(a/4095,V*0.1,-E)*100':lambda a,V,E:fma(a/4095,V*0.1,-E)*100,
 'fma(a/4095,V/10,-E)*100':lambda a,V,E:fma(a/4095,V/10,-E)*100,
 'fma(a*V/4095,0.1,-E)*100':lambda a,V,E:fma(a*V/4095,0.1,-E)*100,
 'fma(a/4095,V,-E*10)/0.1':lambda a,V,E:fma(a/4095,V,-E*10)/0.1,
 'fma(a*V/4095,10,-E*100)':lambda a,V,E:fma(a*V/4095,10,-E*100),
 'fma(a/4095*V,10,-E*100)':lambda a,V,E:fma(a/4095*V,10,-E*100),
 'fma(a/4095,V*10,-E*100)':lambda a,V,E:fma(a/4095,V*10,-E*100),
 'fma(a*(V/4095),10,-E*100)':lambda a,V,E:fma(a*(V/4095),10,-E*100),
 'fma(a,V/4095*10,-E*100)':lambda a,V,E:fma(a,V/4095*10,-E*100),
 'fma(a*0.1/4095... )':lambda a,V,E:fma(a*0.1/4095,V,-E)*100,
 'fma(a/4095/10,V,-E)*100':lambda a,V,E:fma(a/4095/10,V,-E)*100,
 'fma(a/4095*0.1,V,-E)*100':lambda a,V,E:fma(a/4095*0.1,V,-E)*100,
 'fma(a/4095/10*V,100,-E*100)':lambda a,V,E:fma(a/4095/10*V,100,-E*100),
 'fma(a/4095*V/10,100,-E*100)':lambda a,V,E:fma(a/4095*V/10,100,-E*100),
}
res=[(sum(f(a,V,E)==w for a,V,E,w in rows),k) for k,f in C.items()]
for r in sorted(res,reverse=True)[:12]: print(*r)
import pickle; pickle.dump(rows,open('cur_rows.pkl','wb'))
