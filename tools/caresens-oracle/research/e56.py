from gen import *
np.set_printoptions(linewidth=220,precision=5,suppress=True)
E5=math.exp(-.5);A=np.array([[E5,1-E5,0],[0,1,1],[0,0,1]]);G=np.array([.6729,1.761,.1279])
def kal(x,z): p=A@x; return p+G*(z-p[0])
N=60; wo=mkwo(400,1,60,200)[:N]
def rep(nm,sq,t=None,rows=range(0,60),wo=wo,T=34.):
    r,o,g,a,s=run(dev(),wo,T,seq=sq,t=t,snap=True); K=s['out_kalman'];z=g['out_rescale']; out=[]
    for i in rows:
        if i==0: continue
        if r[i]!=1 or o['data_type'][i]: out.append('%d:ret%d/dt%d'%(sq[i],r[i],o['data_type'][i])); continue
        e1=np.abs(kal(K[i-1],z[i])-K[i]).max()<1e-9; e0=np.abs(np.array([z[i],z[i],0])-K[i]).max()<1e-9
        out.append('%d:%s w%d st%d%s'%(sq[i],'K' if e1 else 'init' if e0 else '?',s['warmup_cnt'][i],s['state_init_kalman'][i],'' if o['errcode'][i]==0 else ' e%d'%o['errcode'][i]))
    print(nm,' '.join(out)); return r,o,g,a,s
rep('from100',np.arange(100,115),rows=range(15),wo=wo[:15]); rep('from5',np.arange(5,20),rows=range(15),wo=wo[:15]); rep('from2',np.arange(2,17),rows=range(15),wo=wo[:15])
for gap in (2,3,4,5,6,7,8,10,12,13,14,20,30,100):
    sq=np.arange(1,50); sq[40:]+=gap-1; rep('gap%d'%gap,sq,rows=range(39,49),wo=wo[:49])
