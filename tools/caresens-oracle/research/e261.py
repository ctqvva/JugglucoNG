exec(open('e255.py').read().split("tot=0")[0])
import itertools
tot=0; res={}
def prev(a): b=np.roll(a,1); b[0]=False; return b
def chk(seq,o,g,name):
    global tot
    R=np.round(o['result_glucose']); X=g['tran_inA_5min']; D=g['err4_delay_condi'].astype(bool)
    acc=(o['data_type']!=2)&((o['errcode']&64)==0)
    ph1=seq<2017; ph2=(seq>=865)&(seq<2017)
    I=np.round(g['init_cg']); Rp=np.round(np.roll(o['result_glucose'],1))
    RW1=g['tran_inA'].max(1)
    S=o['smooth_result_glucose']
    G={'R':o['result_glucose'],'init':g['init_cg'],'P':g['err16_CGM_plasma'],'ISF':g['err16_CGM_ISF_smooth'],'resc':g['out_rescale']}
    for k in range(6): G['S%d'%k]=S[:,k]
    cands={}
    for nm,v in G.items():
        cands['D2 %s<40 raw<3'%nm]=(2,ph2&(np.round(v)<40)&(RW1<3))
        cands['D2 %s<39.5? raw<3'%nm]=(2,ph2&(v<40)&(RW1<3))
    cands0={
     'D0 x<1.1&prev':(0,ph1&(X<1.1)&prev(X<1.1)),
     'D1 x<1.5&prev':(1,ph2&(X<1.5)&prev(X<1.5)),
     'D2 R<40&x<3&prev both':(2,ph2&(R<40)&(X<3)&prev((R<40)&(X<3))),
     'D2 R<40&prev R<40 & x<3':(2,ph2&(R<40)&prev(R<40)&(X<3)),
     'D2 init<40&x<3':(2,ph2&(I<40)&(X<3)),
     'D2 R<40&x<3&prevx<3':(2,ph2&(R<40)&(X<3)&prev(X<3)),
     'D2 Rprev<40 & x<3':(2,ph2&(Rp<40)&(X<3)),
    }
    for k,(j,p) in cands.items():
        b=((p&acc)!=D[:,j])&acc; res[k]=res.get(k,0)+int(b.sum())
    tot+=acc.sum()
for x in sv:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(seq,o,g,x['name'])
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(seq,o,g,nm)
print(tot,res)
