from lab import *
def q(**k):
    d=dev(**k); r,o,g,a=run(d,np.full((1,30),10000,'u2'),34.); return int(o['errcode'][0]),int(a['lot_type'][0])
f32=lambda x:np.float32(x)
def nxt(x,d): return np.nextafter(f32(x),f32(d))
for f,v in (('eapp',.0386),('eapp',.113),('eapp',.075),('slope100',2.5),('slope100',8.0),('vref',1.4),('vref',1.6)):
    x=f32(v); print(f,v,'below',q(**{f:nxt(x,-1)}),'at',q(**{f:x}),'above',q(**{f:nxt(x,10)}))
print('nan',q(eapp=np.nan),q(slope100=np.nan),q(vref=np.nan))
def bis(f,a,b,idx=0):
    a=f32(a);b=f32(b); qa=q(**{f:a})[idx]
    while np.nextafter(a,b)!=b:
        m=f32((float(a)+float(b))/2)
        if m==a or m==b: m=np.nextafter(a,b)
        if q(**{f:m})[idx]==qa: a=m
        else: b=m
    return repr(float(a)),repr(float(b)),q(**{f:a}),q(**{f:b})
print(bis('eapp',.0385,.0387),bis('eapp',.112,.1131),bis('eapp',.0749,.0751,1),bis('slope100',8.,8.2),bis('slope100',2.4,2.6),bis('vref',1.39,1.41),bis('vref',1.59,1.61))
