from sc import *
import json
from stress import run as srun, NAMES
sv=json.load(open('err_survey.json')); cs=json.load(open('c0specs.json'))
def rnd(x): return np.where(x<0,-np.floor(-x+0.5),np.floor(x+0.5))
res={}; tot=0
def chk(seq,o,g):
    global tot
    R=rnd(o['result_glucose']); I=rnd(g['init_cg']); D=g['err4_delay_condi'].astype(bool)
    acc=(o['data_type']!=2)&((o['errcode']&64)==0)
    RW=g['tran_inA'].max(1); ph2=(seq>=865)&(seq<2017); ph3=seq>=2017
    c={'D3 R':(3,R<20),'D3 R|I':(3,(R<20)|(I<20)),'D4 R':(4,ph3&(R<30)),'D4 R|I':(4,ph3&((R<30)|(I<30))),
       'D2 R':(2,ph2&(R<40)&(RW<3)),'D2 R|I':(2,ph2&((R<40)|(I<40))&(RW<3)),'D2 R&I':(2,ph2&(R<40)&(I<40)&(RW<3)),
       'D1':(1,ph2&(RW<1.5)),'D1 |R|I': (1,ph2&(RW<1.5))}
    for k,(j,p) in c.items(): res[k]=res.get(k,0)+int(((p!=D[:,j])&acc).sum())
    tot+=acc.sum()
for x in sv[:150]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(seq,o,g)
for sp in cs:
    seq,t,adc,T,c,o,g,s,d=runspec(sp); chk(seq,o,g)
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(seq,o,g)
print(tot,res)
