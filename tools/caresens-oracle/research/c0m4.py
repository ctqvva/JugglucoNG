import pickle,numpy as np
runs=pickle.load(open('c0runs.pkl','rb'))
def model(r,late,N=4,phase=865,perrec=False):
    n=len(r['seq']); C=np.zeros(n,bool); prev_seq=None; H=[]
    for i in range(n):
        if r['typ'][i]==2: H=[]; prev_seq=None; continue
        cons = prev_seq is not None and r['seq'][i]==prev_seq+1
        if not cons: H=[]
        H=(H+[(r['rmax'][i],r['rmean'][i],r['m5'][i],r['seq'][i])])[-N:]
        if len(H)==N:
            mx=max(h[0] for h in H); sl=r['sl']
            early = (H[0][3] if perrec else r['seq'][i])<phase
            if early: C[i]= mx<0.5 and mx*100/sl<10
            else: C[i]= late(H,sl)
        prev_seq=r['seq'][i]
    return C
def score(late,**kw):
    fp=fn=0; ex=[]
    for ri,r in enumerate(runs):
        p=model(r,late,**kw); y=r['C'][:,0].astype(bool); m=r['typ']!=2
        held=np.concatenate([[False],(r['err'][:-1]&4)!=0]); mm=m&~held
        fp+=int((p&~y&mm).sum()); fn+=int((~p&y&mm).sum())
        b=np.nonzero((p!=y)&mm)[0]
        if len(b) and len(ex)<4: ex.append((ri,int(b[0]),int(r['seq'][b[0]]),bool(y[b[0]])))
    return fp,fn,ex
L={
 'mx<.7 & meanmax<.5 & isf(meanmax)<10': lambda H,s: max(h[0] for h in H)<0.7 and max(h[1] for h in H)<0.5 and max(h[1] for h in H)*100/s<10,
 'mx<.7 only': lambda H,s: max(h[0] for h in H)<0.7,
 'mx<.7 & isf(mx)<20': lambda H,s: max(h[0] for h in H)<0.7 and max(h[0] for h in H)*100/s<20,
 'mx<.7 & isf(mx)<25': lambda H,s: max(h[0] for h in H)<0.7 and max(h[0] for h in H)*100/s<25,
 'mx<.7 & isf(mx)<30': lambda H,s: max(h[0] for h in H)<0.7 and max(h[0] for h in H)*100/s<30,
}
if __name__=='__main__':
    for k,f in L.items(): print('%-40s'%k,score(f),score(f,perrec=True)[:2])
