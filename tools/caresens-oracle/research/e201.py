exec(open('e200.py').read().split("f=[n")[0])
f=[n for n in co.DEBUG.names if n.startswith('err16') and g[n].ndim==1]
for n in f:
    v=np.nan_to_num(g[n],nan=-999.)
    ch=np.nonzero(np.diff(v)!=0)[0]+1
    sq=seq[ch]
    print('%-38s changes %5d first seqs %s  diffs %s'%(n,len(ch),sq[:6].tolist(),np.unique(np.diff(sq[:200]))[:8].tolist()))
