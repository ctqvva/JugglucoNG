from e19 import *
def mm(x):
    n=len(x); return np.array([np.median(x[max(0,i-3):min(n,i+3)]) for i in range(n)])
def tm(x): return (np.sum(x)-np.min(x)-np.max(x))/(len(x)-2)
