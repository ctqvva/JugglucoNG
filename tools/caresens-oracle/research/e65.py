from gen import *
np.set_printoptions(linewidth=220,precision=5,suppress=True)
wo=mkwo(800,1,60,200)
def t(w,n0=40,base=wo,T=34.):
    W=np.vstack([base[:n0],np.array(w,'u2')[None],base[n0+1:n0+3]]); r,o,g,a=run(dev(),W,T); return tuple(int(x) for x in (r[n0],o['data_type'][n0],o['errcode'][n0],o['seq_number_final'][n0+1],o['errcode'][n0+1]))+(round(float(o['result_glucose'][n0]),2),)
b=wo[40].astype(int); print('normal',t(b))
def edge(nm,f,xs):
    p_=None
    for x in xs:
        v=t(f(x))[:3]
        if v!=p_: print(nm,x,t(f(x))); p_=v
def setj(*js): 
    def f(x): w=b.copy(); w[list(js)]=np.clip(w[list(js)]+x,0,65535); return w
    return f
edge('one +',setj(10),range(0,40000,50)); edge('one -',setj(10),range(0,-3700,-20))
edge('last +',setj(29),range(0,40000,100)); edge('two +',setj(10,11),range(0,40000,100)); edge('five',setj(10,11,12,13,14),range(0,40000,100))
edge('all',setj(*range(30)),range(-3700,60000,100)); edge('half',setj(*range(15,30)),range(-3700,60000,100))
edge('const',lambda x:np.full(30,x),range(0,65536,64))
