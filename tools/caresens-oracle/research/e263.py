exec(open('e255.py').read().split("tot=0")[0])
rows=[]
def chk(seq,o,g,name):
    acc=(o['data_type']!=2)&((o['errcode']&64)==0); ph2=(seq>=865)&(seq<2017)
    m=acc&ph2
    RW=g['tran_inA'].max(1); RWp=np.roll(RW,1)
    Rp=np.roll(o['result_glucose'],1); Rpp=np.roll(o['result_glucose'],2)
    rows.append(np.stack([g['err4_delay_condi'][m][:,2],o['result_glucose'][m],g['init_cg'][m],RW[m],g['tran_inA_5min'][m],Rp[m],RWp[m],Rpp[m],g['tran_inA_1min'][m].max(1)],1))
for x in sv:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(seq,o,g,x['name'])
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(seq,o,g,nm)
r=np.concatenate(rows); np.save('d2rows.npy',r)
on=r[:,0]==1; cand=(np.round(r[:,1])<40)&(r[:,3]<3)
print('vendor on',on.sum(),'cand',cand.sum(),'on&~cand',(on&~cand).sum(),'cand&~on',(cand&~on).sum())
names=['R','init','raw','5min','Rprev','rawprev','Rpp','max1min']
for j,nm in enumerate(names,1):
    print('%-8s on [%.3f, %.3f]   cand&off [%.3f, %.3f]'%(nm,r[on,j].min(),r[on,j].max(),r[cand&~on,j].min(),r[cand&~on,j].max()))
