import pickle,numpy as np
runs=pickle.load(open('c0runs.pkl','rb'))
def model(r,enter=0.5,stay=0.7,isf_enter=10,isf_stay=None,N=4):
    n=len(r['seq']); C=np.zeros(n,bool); cnt=0; prev_seq=None; hist=[]
    for i in range(n):
        if r['typ'][i]==2: cnt=0; hist=[]; prev_seq=None; continue   # rejected: break
        cons = prev_seq is not None and r['seq'][i]==prev_seq+1
        if not cons: hist=[]
        hist=(hist+[r['rmax'][i]])[-N:]
        m=max(hist)
        ent= len(hist)==N and m<enter and m*100/r['sl']<isf_enter
        st = cons and C[i-1] if i>0 else False
        if st:
            st = r['rmax'][i]<stay and (isf_stay is None or r['rmax'][i]*100/r['sl']<isf_stay)
        C[i]=ent or st
        prev_seq=r['seq'][i]
    return C
def score(**kw):
    tot=fp=fn=0; ex=[]
    for k,r in enumerate(runs):
        p=model(r,**kw); y=r['C'][:,0].astype(bool); m=r['typ']!=2
        b=np.nonzero((p!=y)&m)[0]; fp+=int((p&~y&m).sum()); fn+=int((~p&y&m).sum())
        if len(b) and len(ex)<5: ex.append((k,int(b[0]),bool(y[b[0]])))
    return fp,fn,ex
if __name__=='__main__':
    print('stay .7',score())
    print('stay .7 isf_stay 14',score(isf_stay=14))
    print('stay .7 isf_stay 20',score(isf_stay=20))
