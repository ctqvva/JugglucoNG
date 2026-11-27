import numpy as np
B=np.load('fillB.npy'); t=np.arange(10,301,10.)
L=400.; u=t+50
# y = a + s u + c u^2 + d u^3 with c=-3dL and a+sL-2dL^3=C  => basis: y=a(1-...)...
for i in range(4):
    a=1. if i==2 else 0.; C=1. if i==3 else 0.
    # y(u) = a + s*u + d*(u^3-3L u^2) ; d=(a+s L-C)/(2L^3)
    # linear in s: y = a + (C-a)/(2L^3)*(-1)*(u^3-3Lu^2)... solve LSQ for s
    f0=a+(a-C)/(2*L**3)*(u**3-3*L*u**2); f1=u+L/(2*L**3)*(u**3-3*L*u**2)
    s=np.dot(f1,B[i]-f0)/np.dot(f1,f1); print(i,'s*100=%.6f'%(s*100),'resid*40000',np.abs(f0+s*f1-B[i]).max()*40000, 'resid with cubic fit',np.abs(np.polyval(np.polyfit(t,B[i],3),t)-B[i]).max()*40000)
