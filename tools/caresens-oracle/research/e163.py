from gen import *
wo=mkwo(200,1)
def probe(seqs):
    seqs=np.array(seqs); n=len(seqs); res=[]
    for k in range(n):
        W=wo[:n].copy(); W[k,0]=int(W[k].max()*1.6)
        r,o,g,a,s=run(dev(),W[:k+1],34.,seq=seqs[:k+1],snap=True)
        res.append((int(seqs[k]),int(o['data_type'][k]==2),int(s['contact_err_start_seq'][k])))
    return res
print(probe(list(range(1,12))))
print(probe([1,2,3,4,16,17,18,19,20,21,22,23,24]))
print(probe([1,2,3,4,5,6,7,8,9,10,30,31,32,33,34,35,36,37,38,39,40,41,42]))
print(probe([50,51,52,53,54,60,61,62,63,64,65,66,67,68,69,70]))
print(probe([1,2,3,4,5,6,7,8,9,10,12,13,14,15,16,17,18]))
