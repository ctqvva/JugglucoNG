from e31 import *
print([(c,round(Ev2(tri(near(87)),2,c)*1e6,2)) for c in (5.98,5.99,6,6.01,6.02)])
print([(k,round(Ev2(tri(near(87)*k),2)*1e6,2)) for k in (.998,.999,1.0001,1.001,1.002)])
h=87/90
print([(k,round(Ev2(tri(hm-k),2)*1e6,2)) for k in (2.9,2.95,3.05,3.1)])
K=tri(near(87))
for y,t in DS:
  dl=np.ones(90)
  for it in range(3): f=fit(y,K*dl); dl=mk(np.median)(y-f,it,dl)
  print((pred(f)-t)*1e7)
