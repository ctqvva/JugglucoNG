from e12 import *
rng=np.random.default_rng(6); d=dev(); u=float(d['vref'][0])/40950*100; e=float(d['eapp'][0])*100
A=lambda x:(x+e)/u
n=0; rat=[]
for kind in range(3):
    N=600
    wo=10000+rng.normal(0,[5,30,300][kind],(N,30))
    r,o,g,a,s=run(d,wo.astype('u2'),34.0,snap=True)
    for i in range(1,N):
        x=A(g['tran_inA'][i]); pr=A(s['prev_current'][i-1]);pc=A(s['prev_new_i_sig'][i-1])
        q=A(s['prev_outlier_removed_curr'][i][30:]);full=np.append(pr,x);fc=np.append(pc,q)
        mine=clip(pr,pc,x); er=np.abs(mine-q)
        # detection tested with true clean history
        for j in range(30):
            z=full[j:j+6]; md=np.median(z); mad=np.median(abs(z-md)); s_=1.486*mad; v=x[j]
            ch=abs(q[j]-v)>1e-6
            pred=abs(v-md)>1.5*s_
            exp=fc[j+4]+math.copysign(s_,v-md) if pred else v
            if abs(exp-q[j])>1e-5:
                n+=1
                if n<25: print(kind,i,j,'z-md',np.round(z-md,3),'mad',round(mad,3),'ratio',round(abs(v-md)/s_,4) if s_ else None,'q-v',round(q[j]-v,3),'exp-v',round(exp-v,3),'q-prevclean',round(q[j]-fc[j+4],3),'(q-pc)/s',round((q[j]-fc[j+4])/s_,4) if s_ else None,'clean-raw hist',np.round(fc[j:j+5]-full[j:j+5],2),s['outlier_max_index'][i])
print(n)
