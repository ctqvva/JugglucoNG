from stress import *
f1=[n for n in co.DEBUG.names if n.startswith('err1') or n.startswith('err2')]
for nm in ('noise1000','alt','spike1','halfrec'):
    seq,t,x,T,o,g=run(nm)
    m=np.nonzero((o['data_type']==4)&(g['err4_delay_flag']==0))[0]
    n=np.nonzero((o['data_type']==0)&(g['err4_delay_flag']==0))[0]
    print(nm,'type4 w/o err4 flag',len(m),m[:5])
    for fn in f1:
        a=np.nan_to_num(g[fn][m].reshape(len(m),-1)).astype(float); b=np.nan_to_num(g[fn][n].reshape(len(n),-1)).astype(float)
        if len(m) and ((a!=0).mean()>0.9) and ((b!=0).mean()<0.2): print('   ',fn,'nonzero in type4 %.2f, in type0 %.2f'%((a!=0).mean(),(b!=0).mean()), a[:2].ravel()[:4])
