exec(open('e255.py').read().split("tot=0")[0])
out=[]
def chk(seq,o,g,name):
    R=o['result_glucose']; D=g['err4_delay_condi'].astype(bool); RW=g['tran_inA'].max(1); X=g['tran_inA_5min']
    acc=(o['data_type']!=2)&((o['errcode']&64)==0); ph2=(seq>=865)&(seq<2017)
    p1=ph2&(RW<1.5); p2=ph2&(np.round(R)<40)&(RW<3)
    for j,p in ((1,p1),(2,p2)):
        for i in np.nonzero((p!=D[:,j])&acc)[0][:3]:
            out.append((name,j,int(i),int(seq[i]),'vendor',bool(D[i,j]),'rawmax %.3f prev %.3f'%(RW[i],RW[i-1]),'R %.2f prevR %.2f init %.2f P %s'%(R[i],R[i-1],g['init_cg'][i],g['err16_CGM_plasma'][i]),'5min %.3f'%X[i],'type',int(o['data_type'][i-1])))
for x in sv:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(seq,o,g,x['name'])
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(seq,o,g,nm)
for r in out[:30]: print(*r)
