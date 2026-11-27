from sc import *
import json
from stress import run as srun, NAMES
sv=json.load(open('err_survey.json')); cs=json.load(open('c0specs.json'))
def rnd(x): return np.where(x<0,-np.floor(-x+0.5),np.floor(x+0.5))
res={}; tot=0
def chk(seq,o,g,s):
    global tot
    R=rnd(o['result_glucose']); I=rnd(g['init_cg']); D=g['err4_delay_condi'].astype(bool)
    acc=(o['data_type']!=2)&((o['errcode']&64)==0)
    RW=g['tran_inA'].max(1)
    # 2-record max (previous accepted record with seq-1)
    prev=np.full(len(seq),-np.inf); 
    for i in range(1,len(seq)):
        j=i-1
        while j>=0 and not acc[j]: j-=1
        if j>=0 and seq[j]==seq[i]-1 and acc[j]: prev[i]=RW[j]
    R2=np.maximum(RW,prev)
    idx=s['idx'].astype(int)  # accepted count after record
    for unit,u in (('seq',seq),('idx',idx),('idx-1',idx-1)):
        p1=u<2017; p2=(u>=865)&(u<2017); p3=u>=2017
        c={'D0':(0,p1&(R2<1.1)),'D1 N1':(1,p2&(RW<1.5)),'D1 N2':(1,p2&(R2<1.5)),'D2 N1':(2,p2&(R<40)&(RW<3)),'D2 N2':(2,p2&(R<40)&(R2<3)),
           'D3 R|I':(3,(R<20)|(I<20)),'D4 R':(4,p3&(R<30)),'D4 R|I':(4,p3&((R<30)|(I<30)))}
        for k,(j,p) in c.items(): res[unit+' '+k]=res.get(unit+' '+k,0)+int(((p!=D[:,j])&acc).sum())
    tot+=acc.sum()
for x in sv[:150]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec'],True); chk(seq,o,g,s)
for sp in cs:
    seq,t,adc,T,c,o,g,s,d=runspec(sp,True); chk(seq,o,g,s)
print(tot); 
for k in sorted(res): print(k,res[k])
