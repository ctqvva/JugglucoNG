from e7 import *
from collections import Counter
C=Counter(); cond=[]; bad=[]
for tr in range(80):
    w1=rng.normal(10000,20,30).astype('u2'); q,m,oi,x,mmv,q0=two(w1)
    full=np.concatenate([w0,w1]).astype(float)
    for j in range(30):
        zr=full[30+j-5:30+j];zc=q[30+j-5:30+j];xx=full[30+j];v=q[30+j]
        c={}
        for wn,z in(('r',zr),('c',zc)):
            for self_ in ('r',):
                zz=np.append(z,xx); md=np.median(zz); s=1.486*np.median(abs(zz-md)); c[wn]=(md,s)
        tags=[]
        if abs(v-xx)<1e-6: tags.append('same')
        for bn,bv in(('praw',zr[4]),('pcl',zc[4]),('medr',c['r'][0]),('medc',c['c'][0])):
            for sn in 'rc':
                for sg in(1,-1):
                    for k in (1,1.5,2,3):
                        if abs(v-(bv+sg*k*c[sn][1]))<1e-6: tags.append((bn,sn,sg*k))
        eq=np.abs(zr-zc).max()<1e-6
        if not tags: bad.append((j,zr,zc,xx,v))
        C[(eq,tags[0] if len(tags) else 'none',len(tags)>1)]+=1
        if eq: cond.append((abs(xx-c['r'][0])/c['r'][1] if c['r'][1] else 99,abs(v-xx)>1e-6))
for k,v in C.most_common(): print(k,v)
c=np.array(cond); print('min chg',c[c[:,1]==1,0].min(),'max same',c[c[:,1]==0,0].max())
for b in bad[:6]: print(b)
