from lvlprobe import *

for hi in (49,50,51,149,150,151,299,300,301,599,600,601):
    print('hi',hi,level(1000,hi,0,125,25))
for lo in (49,50,51):
    print('lo',lo,level(1000,0,lo,125,25), level(1000,700,lo,125,25))
for mean in (60,80,100,154,155,200,300):
    print('mean',mean,level(1000,0,0,mean,25),level(1000,0,0,mean,15))
