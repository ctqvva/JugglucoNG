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
    M1=g['tran_inA_1min'].max(1); RW=g['tran_inA'].max(1); m1=g['tran_inA_1min'].min(1)
    cands={
     'D0 max1min<1.1':(0,ph1&(M1<1.1)),'D0 maxraw<1.1':(0,ph1&(RW<1.1)),
     'D1 max1min<1.5':(1,ph2&(M1<1.5)),'D1 maxraw<1.5':(1,ph2&(RW<1.5)),
     'D2 R<40&max1min<3':(2,ph2&(R<40)&(M1<3)),'D2 R<40&maxraw<3':(2,ph2&(R<40)&(RW<3)),
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
