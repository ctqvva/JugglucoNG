from gen import *
wo=mkwo(120,1)
def init_after(nrej,g,tshift=0):
    seqs=list(range(1,41)); spikes=[]
    for k in range(nrej): seqs.append(41+k); spikes.append(40+k)
    seqs.append(40+nrej+g); W=wo[:len(seqs)].copy()
    for i in spikes: W[i,0]=int(W[i].max()*1.6)
    sq=np.array(seqs); t=BASE+300*sq; t[-1]+=tshift
    r,o,g_,a,s=run(dev(),W,34.,seq=sq,t=t,snap=True)
    assert all(o['data_type'][i]==2 for i in spikes)
    return int(s['warmup_cnt'][-1])==1
for nrej in (1,2,3,4):
    print(nrej,'rejected; init for gap g after last rejected:',{g:init_after(nrej,g) for g in range(1,7)})
print('time shift effect (1 rej, g=2):',{ts:init_after(1,2,ts) for ts in (-200,-100,0,100,200,300,600)})
print('time shift effect (1 rej, g=3):',{ts:init_after(1,3,ts) for ts in (-600,-300,-200,-100,0)})
print('====')
def thr(nrej,g):
    lo,hi=-1500,1500
    while hi-lo>1:
        m=(lo+hi)//2
        if init_after(nrej,g,m): hi=m
        else: lo=m
    return 300*(nrej+g)+hi
for nrej in (1,2,3):
    print(nrej,[thr(nrej,g) for g in (1,2,3)])
