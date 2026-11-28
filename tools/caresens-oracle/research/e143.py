from gen import *
np.set_printoptions(linewidth=220,precision=2,suppress=True)
for seed,(lo,hi) in enumerate(((-20,60),(0,60),(20,50),(30,45),(10,40))):
    w=mkwo(1500,seed,lo,hi,noise=12); r,o,g,a,s=run(dev(),w,34.,snap=True)
    dt=o['data_type'];G=o['result_glucose']
    ch=np.nonzero(np.diff(dt.astype(int)))[0]+1
    print(seed,'n changes',len(ch))
    for c in ch[:5]: print(' ',c+1,'->',dt[c],'G',G[c-3:c+3],'err',o['errcode'][c-1:c+1])
