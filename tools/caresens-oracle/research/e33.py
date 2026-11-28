from e31 import *
K=tri(near(87))
def sm(y):
    dl=np.ones(90)
    for it in range(3): f=fit(y,K*dl); dl=mk(np.median)(y-f,it,dl)
    return f
A_=[];B_=[[] for _ in range(5)]
# regress each minute's output on f[57..89]
for y,t in DS: A_.append(sm(y)[60:])
A_=np.array(A_); T_=np.array([t for y,t in DS])
for k in range(5):
    # local: assume f approx quadratic -> effective position & second moment
    f=A_; sl=f[:,6*k+3]-f[:,6*k+2]
    print(k,'pos',[round(6*k+2+float(p),4) for p in ((T_[:,k]-f[:,6*k+2])/sl)[:6]])
