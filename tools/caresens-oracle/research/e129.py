from e126 import go,np,BASE,Counter
print('&&&&&')
for sg,ex in ((2,1080),(2,1079),(3,1080),(5,1079),(5,1080)):
    sq=np.arange(1,1500); t=BASE+300*sq
    for j in range(4): sq[300+j*50:]+=sg-1; t[300+j*50:]+=300*(sg-1)+ex
    e,g,s,o=go(sq,t); print(sg,ex,s['err32_n'][600])
from gen import mkwo
from lab import run,dev
for lev in (0,3,5,8,10,15,20):
    w=mkwo(1000,1,level=lev); r,o,g,a,s=run(dev(),w,34.,snap=True); e=o['errcode']; nz=np.nonzero(g['error_code8'])[0]
    print(lev,'e8 first',nz[0]+1 if len(nz) else None,len(nz),'glu',o['result_glucose'][[100,500]].round(1),'curr',g['tran_inA_5min'][[100,500]].round(3),'condi',g['err8_condi'][[300,384,385,386,500]].tolist())
