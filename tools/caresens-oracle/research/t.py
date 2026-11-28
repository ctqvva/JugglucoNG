import sys,os,time; sys.path.insert(0,'../../h'); sys.path.insert(0,'.'); os.chdir('../../h')
from run import *; from o import Vend
v=Vend(); e=Algo(); v.reset(defdev()); e.reset(defdev())
t=time.time()
for s in range(1,120):
    wo=orig_wo(s,pnormal); a=v.step(s,1709726400+s*300,wo,34.2); b=e.step(s,1709726400+s*300,wo,34.2)
    assert a==b,(s,[i for i in range(155) if a[1][i]!=b[1][i]][:10])
    assert v.args()==e.args(),s
print('identical to emulator',time.time()-t)
t=time.time(); v.reset(defdev())
for s in range(1,4321): v.step(s,1709726400+s*300,orig_wo(s%274,pnormal),34.2)
print(time.time()-t)
