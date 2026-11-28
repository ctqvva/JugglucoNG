from e79 import *
print('=====')
for st in (1,2,3,4,5,6,7,8,10,12,13,24,50,100,280,290,1000):
    fl=[]
    for k in range(1,60):
        sq=np.arange(st,st+k+1)
        r,o,g,a=run(dev(),np.vstack([wo[:k],spikew(wo[k])]),34.,seq=sq); fl.append(int(o['data_type'][-1]))
    fl=np.array(fl); print(st,'first flagged seq',(st+1+np.argmax(fl>0)) if fl.any() else None,'always after',fl[np.argmax(fl>0):].all(), 'start_seq',a['start_seq'],a['contact_err_start_seq'],a['prev_contact_err_algo_seq'],a['idx'])
# two flagged in a row, and after
W=wo[:60].copy()
for j in range(20,40): W[j]=spikew(W[j])
r,o,g,a,s=run(dev(),W,34.,snap=True); print(o['data_type'][15:45]); print(s['contact_err_start_seq'][15:45]); print(s['prev_contact_err_algo_seq'][15:45]); print(o['errcode'][15:45])
