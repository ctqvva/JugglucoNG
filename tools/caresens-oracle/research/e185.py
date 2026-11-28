import pickle,numpy as np
from fractions import Fraction as Fr
rows=pickle.load(open('cur_rows.pkl','rb'))
A=np.array([r[0] for r in rows],float); V=np.array([r[1] for r in rows]); E=np.array([r[2] for r in rows]); W=np.array([r[3] for r in rows])
f=np.float32
def fmaN(x,y,z): return np.array([float(Fr(p)*Fr(q)+Fr(r)) for p,q,r in zip(np.broadcast_to(x,A.shape),np.broadcast_to(y,A.shape),np.broadcast_to(z,A.shape))])
volt={
 'a*V/40950':A*V/40950,'a/40950*V':A/40950*V,'a*(V/40950)':A*(V/40950),'a/4095*V/10':A/4095*V/10,'a*V/4095/10':A*V/4095/10,
 'a*V/4095*0.1':A*V/4095*0.1,'a/4095*V*0.1':A/4095*V*0.1,'a*V*1e-4/4.095':A*V*1e-4/4.095,'a/4095*(V/10)':A/4095*(V/10),
 'a*(V/10)/4095':A*(V/10)/4095,'a*V/4095':A*V/4095,'a/4095*V':A/4095*V,'(a/4095)*V':(A/4095)*V,'a*(V/4095)':A*(V/4095),
 'V*a/40950':V*A/40950, 'f32(a/4095)*V/10': A.astype(f).__truediv__(f(4095)).astype(float)*V/10,
 'f32(a/4095*V)/10': ((A.astype(f)/f(4095))*V.astype(f)).astype(float)/10,
 'f32(a*V/40950)': ((A.astype(f)*V.astype(f))/f(40950)).astype(float),
 'f32(a*V)/40950': (A.astype(f)*V.astype(f)).astype(float)/40950,
 'f32(a/40950)*V': (A.astype(f)/f(40950)).astype(float)*V,
}
res=[]
for vk,x in volt.items():
    ten = vk in ('a*V/4095','a/4095*V','(a/4095)*V','a*(V/4095)')
    if ten:
        forms={'(x-E*10)*10':(x-E*10)*10,'(x/10-E)*100':(x/10-E)*100,'x*10-E*100':x*10-E*100,'(x*0.1-E)*100':(x*0.1-E)*100,'(x-10*E)/0.1':(x-10*E)/0.1,'(x/10-E)/0.01':(x/10-E)/0.01}
    else:
        forms={'(x-E)*100':(x-E)*100,'(x-E)/0.01':(x-E)/0.01,'x*100-E*100':x*100-E*100,'x/0.01-E/0.01':x/0.01-E/0.01,'(x-E)*1e3/10':(x-E)*1e3/10,'(x-E)*1000*0.1':(x-E)*1000*0.1,'(x-E)/1e-2':(x-E)/1e-2,'(x-E)*1e2':(x-E)*1e2,'(x*1000-E*1000)/10':(x*1000-E*1000)/10,'(x-E)*10*10':(x-E)*10*10,'f32(x-E)*100':(x-E).astype(f).astype(float)*100,'(x-f32E)':(x-E)*100}
    for fk,y in forms.items(): res.append((int((y==W).sum()),vk,fk))
for r in sorted(res,reverse=True)[:10]: print(*r)
d=(A*V/40950-E)*100
print('ulps eng', np.unique(np.round((d-W)/np.spacing(W)),return_counts=True))
