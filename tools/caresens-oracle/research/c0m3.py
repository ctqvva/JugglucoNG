import pickle,numpy as np
runs=pickle.load(open('c0runs.pkl','rb'))
def model(r,raw_early=0.5,raw_late=0.7,lev_thr=0.5,isf=10,N=4,phase=865,levfn='mean'):
    n=len(r['seq']); C=np.zeros(n,bool); prev_seq=None; hist=[]; hl=[]
    for i in range(n):
        if r['typ'][i]==2: hist=[]; hl=[]; prev_seq=None; continue
        cons = prev_seq is not None and r['seq'][i]==prev_seq+1
        if not cons: hist=[]; hl=[]
        hist=(hist+[r['rmax'][i]])[-N:]; hl=(hl+[r['rmean'][i] if levfn=='mean' else r['m5'][i]])[-N:]
        rt=raw_early if r['seq'][i]<phase else raw_late
        x=max(hl)
        C[i]= len(hist)==N and max(hist)<rt and x<lev_thr and x*100/r['sl']<isf
        prev_seq=r['seq'][i]
    return C
def score(**kw):
    fp=fn=0; ex=[]
    for ri,r in enumerate(runs):
        p=model(r,**kw); y=r['C'][:,0].astype(bool); m=r['typ']!=2
        # vendor reports condi 0 while error already latched? exclude records where err4 already set at previous record
        held=np.concatenate([[False],(r['err'][:-1]&4)!=0])
        mm=m&~held
        fp+=int((p&~y&mm).sum()); fn+=int((~p&y&mm).sum())
        b=np.nonzero((p!=y)&mm)[0]
        if len(b) and len(ex)<6: ex.append((ri,int(b[0]),int(r['seq'][b[0]]),bool(y[b[0]])))
    return fp,fn,ex
if __name__=='__main__':
    print(score())
    print('levfn 5min',score(levfn='5min'))
    print('no raw early',score(raw_early=0.7))
