from sc import *
from fractions import Fraction as Fr
def fma(x,y,z): return float(Fr(x)*Fr(y)+Fr(z))
rows=[]
for sd,sl,vr,ea in ((1,3.1,1.43,0.05),(2,6.2,1.58,0.1),(3,4.4,1.5,0.08)):
    spec=dict(seed=sd,readings=150,start=1,slope100=sl,vref=vr,eapp=ea,noise=25,lo=40,hi=400)
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    V=float(d['vref'][0]); E=float(d['eapp'][0])
    for i in range(len(seq)):
        for j in range(30): rows.append((float(adc[i][j]),V,E,float(g['tran_inA'][i][j])))
import pickle; pickle.dump(rows,open('tran_rows.pkl','wb'))
C={'eng':lambda a,V,E:(a*V/40950-E)*100,'a/40950*V':lambda a,V,E:(a/40950*V-E)*100,'fma(a/40950,V,-E)*100':lambda a,V,E:fma(a/40950,V,-E)*100,
   'a/4095*V/10':lambda a,V,E:(a/4095*V/10-E)*100,'(a*V/4095-E*10)*10':lambda a,V,E:(a*V/4095-E*10)*10,
   'exact':lambda a,V,E:float((Fr(a)*Fr(V)/40950-Fr(E))*100),'a*(V/40950)':lambda a,V,E:(a*(V/40950)-E)*100}
for k,f in C.items(): print(sum(f(a,V,E)==w for a,V,E,w in rows),k)
print(len(rows))
