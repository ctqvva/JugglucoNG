exec(open('e177.py').read().split("print('case142'")[0])
hl=((0,0),(49,0),(50,0),(149,0),(150,0),(299,0),(300,0),(599,0),(600,0),(0,49),(0,50),(300,50),(600,50))
print('hi/lo',hl)
for cv in (19.99,20.01,29.99,30.01,35.99,36.01,40,60):
    print('cv',cv,[level(1000,hi,lo,125,cv)[0] for hi,lo in hl])
# exact CV computed values
for cv in (20,30,36):
    print(cv,level(1000,0,0,125,cv))
