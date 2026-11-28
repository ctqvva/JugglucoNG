from sc import *
import json
sv=json.load(open('err_survey.json')); cs=json.load(open('c0specs.json'))
def rnd(x): return np.where(x<0,-np.floor(-x+0.5),np.floor(x+0.5))
res={}; tot=0
def chk(seq,o,g,s):
    global tot
    R=rnd(o['result_glucose']); I=rnd(g['init_cg']); D=g['err4_delay_condi'].astype(bool)
    acc=(o['data_type']!=2)&((o['errcode']&64)==0)
    RW=g['tran_inA'].max(1)
    prev=np.full(len(seq),-np.inf)
    for i in range(1,len(seq)):
        j=i-1
        while j>=0 and not acc[j]: j-=1
        if j>=0 and seq[j]==seq[i]-1: prev[i]=RW[j]
    R2=np.maximum(RW,prev)
    idx=s['idx'].astype(int)
    for a2,u2 in (('seq',seq),('idx',idx)):
        for a3,u3 in (('seq',seq),('idx',idx)):
            p2=(u2>=865)&(u3<2017); p1=u3<2017; p3=u3>=2017
            for nm,(j,p) in {'D0':(0,p1&(R2<1.1)),'D1':(1,p2&(RW<1.5)),'D2':(2,p2&(R<40)&(RW<3)),'D4':(4,p3&(R<30))}.items():
                key='%s ph2:%s ph3:%s'%(nm,a2,a3); res[key]=res.get(key,0)+int(((p!=D[:,j])&acc).sum())
    tot+=acc.sum()
for x in sv[:150]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec'],True); chk(seq,o,g,s)
for sp in cs:
    seq,t,adc,T,c,o,g,s,d=runspec(sp,True); chk(seq,o,g,s)
print(tot)
for k in sorted(res): print(k,res[k])
