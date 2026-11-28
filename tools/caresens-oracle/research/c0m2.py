import pickle,numpy as np
runs=pickle.load(open('c0runs.pkl','rb'))
def model(r,lev,N=4,rawthr=0.7):
    n=len(r['seq']); C=np.zeros(n,bool); prev_seq=None; hist=[]; hl=[]
    for i in range(n):
        if r['typ'][i]==2: hist=[]; hl=[]; prev_seq=None; continue
        cons = prev_seq is not None and r['seq'][i]==prev_seq+1
        if not cons: hist=[]; hl=[]
        hist=(hist+[r['rmax'][i]])[-N:]; hl=(hl+[lev(r,i)])[-N:]
        x=max(hl)
        C[i]= len(hist)==N and max(hist)<rawthr and x<0.5 and x*100/r['sl']<10
        prev_seq=r['seq'][i]
    return C
def score(lev,**kw):
    fp=fn=0
    for r in runs:
        p=model(r,lev,**kw); y=r['C'][:,0].astype(bool); m=r['typ']!=2
        fp+=int((p&~y&m).sum()); fn+=int((~p&y&m).sum())
    return fp,fn
for nm,lev in (('5min',lambda r,i:r['m5'][i]),('raw mean',lambda r,i:r['rmean'][i]),('raw max',lambda r,i:r['rmax'][i])):
    print(nm,'max over 4:',score(lev))
