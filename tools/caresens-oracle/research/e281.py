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
    RW=g['tran_inA'].max(1); M5=g['tran_inA_5min']
    prev=np.full(len(seq),-np.inf)
    for i in range(1,len(seq)):
        j=i-1
        while j>=0 and not acc[j]: j-=1
        if j>=0 and seq[j]==seq[i]-1: prev[i]=RW[j]
    R2=np.maximum(RW,prev)
    p1=seq<2017; p2=(seq>=865)&(seq<2017); p3=seq>=2017
    c={'D0 R2<1.1':(0,p1&(R2<1.1)),'D0 R2<1.1 & I<40':(0,p1&(R2<1.1)&(I<40)),'D0 I<x?':(0,p1&(R2<1.1)),
       'D1 I<40&raw<1.5':(1,p2&(I<40)&(RW<1.5)),'D1 I<40&5min<1.5':(1,p2&(I<40)&(M5<1.5)),'D1 I<40':(1,p2&(I<40)),
       'D2 R<40&raw<3':(2,p2&(R<40)&(RW<3)),'D2 I<40&raw<3':(2,p2&(I<40)&(RW<3)),'D2 (R|I)<40 & raw<3':(2,p2&((R<40)|(I<40))&(RW<3)),'D2 I<40 & 5min<3':(2,p2&(I<40)&(M5<3)),
       'D3 R|I<20':(3,(R<20)|(I<20)),'D4 R<30':(4,p3&(R<30)),'D4 I<30':(4,p3&(I<30)),'D4 R|I<30':(4,p3&((R<30)|(I<30)))}
    for k,(j,p) in c.items(): res[k]=res.get(k,0)+int(((p!=D[:,j])&acc).sum())
    tot+=acc.sum()
for x in sv[:150]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(seq,o,g)
for sp in cs:
    seq,t,adc,T,c,o,g,s,d=runspec(sp); chk(seq,o,g)
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(seq,o,g)
print(tot)
for k in sorted(res): print('%-26s %d'%(k,res[k]))
