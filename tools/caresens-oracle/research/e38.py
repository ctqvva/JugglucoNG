from e36 import *
dd=[]
for k in range(8):
    rr=np.random.default_rng(k); n=rr.normal(0,.1,60); dd.append((go([0,0,0],n),sm(np.append(n,np.zeros(30)))[60:]))
def test(name,fn): print(name,np.sqrt(np.mean([(np.array(fn(f))-t)**2 for t,f in dd],axis=0))*1e6)
ker=np.array([-1,5,3,14,3,5,-1])/28
def full(f,M=lambda f:np.concatenate([mm(z) for z in f.reshape(5,6)])):
    m=np.concatenate([[0,0,0],M(f),[np.nan]*3]); s=np.convolve(m,ker,'valid'); return [tm(z) for z in s.reshape(5,6)]
test('grp',full); test('mm30',lambda f:full(f,mm)); test('f',lambda f:full(f,lambda f:f))
print(M)
# param dependence
for p in ('w_sg_x100','cal_noise','shift_m2_x100','kalman_q_x100','contact_win_len','fill_flag'):
    d[p][0]=d[p][0]//2 if d[p].dtype.kind!='f' else d[p][0]*.5
    b=go([1,2,-1.],np.zeros(60)); print(p,b)
    d[:]=dev()
print(go([1,2,-1.],np.zeros(60)))
print('====')
K7=np.array([-1,4,7,8,7,4,-1.])
def smk(m,p3,mode):
    x=np.append(p3,m); n=len(x); out=[]
    for i in range(3,n):
        idx=np.arange(i-3,i+4); ok=(idx>=0)&(idx<n)
        if mode=='renorm': out.append((K7[ok]*x[idx[ok]]).sum()/K7[ok].sum())
        elif mode=='raw': out.append((K7[ok]*x[idx[ok]]).sum()/28)
        elif mode=='keep': out.append((K7*x[idx]).sum()/28 if ok.all() else x[i])
        elif mode=='rep': out.append((K7*x[np.clip(idx,0,n-1)]).sum()/28)
        elif mode=='refl': out.append((K7*x[np.where(idx>n-1,2*(n-1)-idx,idx)]).sum()/28)
    return np.array(out)
for mode in ('renorm','raw','keep','rep','refl'):
    test(mode,lambda f:[tm(z) for z in smk(np.concatenate([mm(z) for z in f.reshape(5,6)]),[0,0,0],mode).reshape(5,6)])
