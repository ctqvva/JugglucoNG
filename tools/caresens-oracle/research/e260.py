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
    # seq-slot aware max over last N seq numbers (missing seq -> NaN -> comparison false)
    def mxs(N):
        out=np.full(len(seq),np.inf)
        pos={int(q):i for i,q in enumerate(seq)}
        for i,q in enumerate(seq):
            vals=[]
            for k in range(N):
                j=pos.get(int(q)-k)
                vals.append(RW1[j] if (j is not None and j<=i and acc[j]) else np.nan)
            out[i]=np.max(vals)
        return out
    def mx(N):
        r=RW1.copy()
        for k in range(1,N): r=np.maximum(r,np.concatenate([np.full(k,np.inf),RW1[:-k]]))
        return r
    cands={}
    for N in (1,2):
        r=mx(N); rs=mxs(N)
        cands['D0 N%d'%N]=(0,ph1&(r<1.1)); cands['D0 slots N%d'%N]=(0,ph1&(rs<1.1))
        cands['D1 N%d'%N]=(1,ph2&(r<1.5)); cands['D1 slots N%d'%N]=(1,ph2&(rs<1.5))
        cands['D2 R&I N%d'%N]=(2,ph2&(R<40)&(I<40)&(r<3)); cands['D2 R&I slots N%d'%N]=(2,ph2&(R<40)&(I<40)&(rs<3))
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
