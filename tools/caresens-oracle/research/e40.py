from cur import *
rng=np.random.default_rng(1); d=dev(); wo=rng.normal(10000,30,(40,30)).astype('u2'); wo+=(np.arange(40)*50)[:,None].astype('u2')
def probe(dts,seqs=None):
    n=len(dts); tt=BASE+300+np.cumsum(dts); sq=np.arange(1,n+1) if seqs is None else np.array(seqs)
    r,o,g,a,s=run(d,wo[:n],34.0,seq=sq,t=tt,snap=True); out=[]
    for i in range(1,n):
        x=g['tran_inA'][i]; pq=s['prev_outlier_removed_curr'][i-1]; p3=s['prev_mov_median_curr'][i-1]
        z=np.zeros(5)
        hist=[('hist',(s['prev_current'][i-1],s['prev_new_i_sig'][i-1],s['outlier_max_index'][i-1]))]
        q=s['prev_outlier_removed_curr'][i][30:]
        c=clip(*hist[0][1],x)[0]
        cl='clip' if np.abs(q-c).max()<1e-9 else 'raw' if np.abs(q-x).max()<1e-9 else '%d differ'%((np.abs(q-x)>1e-9).sum())
        t=g['tran_inA_1min'][i]; m={}
        for nm,y in (('none',q),('loess',loess(np.append(pq,q))[60:])):
            m[nm]=mins(gm(y));m[nm+'+smk']=mins(smk(gm(y),p3))
        best=min(m,key=lambda k:np.abs(m[k]-t).max()); out.append((cl,best,float('%.0e'%np.abs(m[best]-t).max()),o['data_type'][i],o['errcode'][i],int(r[i]),o['seq_number_final'][i]))
    return out
