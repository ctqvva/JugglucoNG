from e67 import *
print('=====')
def spike(ps,hi,lo): w=np.full(30,lo); w[list(ps)]=hi; return w
print('single',[p_ for p_ in range(30) if ctx(spike([p_],30000,10000),10000)==2])
print('pairs',[(a_,b_) for a_ in range(8) for b_ in range(a_+1,10) if ctx(spike([a_,b_],30000,10000),10000)==2])
print('threshold of single at 0, lo=10000',[hi for hi in range(10000,16000,20) if ctx(spike([0],hi,10000),10000)==2][:2],' lo=5000',[hi for hi in range(5000,9000,20) if ctx(spike([0],hi,5000),5000)==2][:2],' lo=20000',[hi for hi in range(20000,32000,50) if ctx(spike([0],hi,20000),20000)==2][:2])
print('prev effect lo=10000',[(pv,[hi for hi in range(10000,60000,50) if ctx(spike([0],hi,10000),pv)==2][:1]) for pv in (3000,5000,8000,9500,9900,10000,10100,10500,12000,20000)])
print('only first reading n',[n for n in range(0,15) if ctx(spike([0],30000,10000),10000,n)==2])
