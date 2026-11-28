from gen import *
d=dev()
def after(prev5,cur):
    # build state: first reading constant then second reading starting with given samples
    W=np.full((3,30),10000,'u2'); W[1,25:]=prev5; W[2,:len(cur)]=cur; W[2,len(cur):]=cur[-1]
    r,o,g,a,s=run(d,W,34.,snap=True); x=g['tran_inA'][2]; q=s['prev_outlier_removed_curr'][2][30:]
    return [round(float(q[j]-x[j]),5) for j in range(len(cur))]
print('5 equal then +1:',after([10000]*5,[10001])); print('5 equal then +3:',after([10000]*5,[10003]))
print('4 equal+1 then +3:',after([10000]*4+[10001],[10003])); print('[a,a,a,b,a] +3',after([10000,10000,10000,10001,10000],[10003]))
print('5 equal then -3:',after([10000]*5,[9997])); print('5 equal then +30:',after([10000]*5,[10030]))
def inrec(row):
    W=np.full((3,30),10000,'u2'); W[2]=row
    r,o,g,a,s=run(d,W,34.,snap=True); x=g['tran_inA'][2]; q=s['prev_outlier_removed_curr'][2][30:]
    return np.round(q-x,5)
r=np.full(30,10000); r[9]=10001; print('in-record 9 equal then +1 at 9:',inrec(r)[:12])
r=np.full(30,10000); r[9]=10003; print('+3 at 9:',inrec(r)[:12])
r=10000+np.array([1,0]*15); r[4:9]=10000; r[9]=10003; print('alt then 5 eq:',inrec(r)[:12])
r=np.full(30,10000); r[0]=10003; print('+3 at 0:',inrec(r)[:4])
r=np.full(30,10000); r[5]=10003; print('+3 at 5:',inrec(r)[:7])
