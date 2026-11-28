from gen import *
np.set_printoptions(linewidth=220,precision=8,suppress=True)
F=lambda c:30*(1-c/180)*(1-c/385)
for kw in (dict(),dict(slope100=5.0),dict(eapp=.05),dict(eapp=.05,slope100=5.,vref=1.45),dict(slope100=2.6),dict(slope100=8.)):
    d=dev(**kw); e=[]
    for lev in list(range(-30,700,7)):
        # single reading at seq s with given ADC
        for sq in (1,5,288,289,300,400,431,432,433,600):
            cur=lev*float(d['slope100'][0])/100*.95+.5; w=int(max(cur/100+float(d['eapp'][0]),0)*40950/float(d['vref'][0]))
            r,o,g,a=run(d,np.full((1,30),w,'u2'),34.,seq=[sq]); c=g['init_cg'][0]; kk=g['out_rescale'][0]-c
            tp=1 if sq<=288 else max(0,(432-sq)/144); ex=F(c)*tp if c<180 else 0
            e.append((abs(kk-ex),sq,c,kk,ex,o['errcode'][0],g['out_iir'][0]))
    e.sort(key=lambda z:-z[0]); print(kw,[tuple(float(v) for v in z) for z in e[:4]], 'n bad',sum(z[0]>1e-9 for z in e),'min c',min(z[2] for z in e), 'iir min',min(z[6] for z in e))
