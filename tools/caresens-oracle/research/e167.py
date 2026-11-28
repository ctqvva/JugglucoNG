from gen import *
def inrec(vref,dv=1,j=9,noisy=False):
    d=dev(vref=vref); W=np.full((3,30),10000,'u2')
    if noisy: W[2]=10000+np.array([0,7,-6,9,-8]*6); W[2][4:9]=10000
    W[2][j]=10000+dv
    r,o,g,a,s=run(d,W,34.,snap=True); x=g['tran_inA'][2]; q=s['prev_outlier_removed_curr'][2][30:]
    return abs(q[j]-x[j])>1e-12
lo,hi=1.40,1.6
# find vref threshold where +1 ADC switches from keep to clip
vs=np.linspace(1.40,1.6,201); res=[inrec(v) for v in vs]
sw=[(round(vs[i],4),res[i]) for i in range(1,len(vs)) if res[i]!=res[i-1]]; print('switch points',sw,'first',res[0],'last',res[-1])
for v in (1.43,1.5):
    print(v,'delta nA',v/40950*100,{dv:inrec(v,dv) for dv in (1,2,3)})
a,b=np.float32(1.474),np.float32(1.475)
while np.nextafter(a,b)!=b:
    m=np.float32((float(a)+float(b))/2)
    if m in (a,b): break
    if inrec(float(m)): b=m
    else: a=m
for v in (a,b):
    x=lambda adc:(adc*float(v)/40950-0.10067*1)*100
    print(repr(float(v)),'delta',(10001*float(np.float32(v))/40950-float(np.float32(.10067)))*100-(10000*float(np.float32(v))/40950-float(np.float32(.10067)))*100)
# does the floor also apply when MAD>0? noisy window where 1.5sigma small
print('noisy window +1:',inrec(1.43,1,noisy=True),inrec(1.5,1,noisy=True))
