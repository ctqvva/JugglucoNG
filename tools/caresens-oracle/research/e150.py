from gen import *
wo=mkwo(120,1)
def tst(spikes, seqs):
    W=wo[:len(seqs)].copy()
    for i in spikes: W[i,0]=int(W[i].max()*1.6)
    r,o,g,a,s=run(dev(),W,34.,seq=np.array(seqs),snap=True)
    return [(int(seqs[k]),int(o['data_type'][k]),int(s['warmup_cnt'][k])) for k in range(38,len(seqs))]
base=list(range(1,41))
print('spike then +2 then normal then gap 3:',tst([40],base+[41,44,45,49,50]))
print('two spikes consecutive then +1:',tst([40,41],base+[41,42,43,44]))
print('two spikes then +2:',tst([40,41],base+[41,42,44,45]))
print('spike at 41 then 45 (diff from rejected 4, from accepted 5):',tst([40],base+[41,45,46]))
print('spike seq 41, next 43 time big:',tst([40],base+[41,43,44]))
