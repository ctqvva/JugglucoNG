


rgb2hsv: rgb2hsv.o nanovg/src/nanovg.o
	g++ -std=c++20 -g $^ -o $@

#CXX=/home/jka/Android/Sdk/ndk/25.0.8775105/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang++
#CXX=g++-14
CXXFLAGS=-std=c++26
%.o:%.cpp
	$(CXX) -std=c++26 -g -I nanovg/src -c $^ 

tags:
	ctags -R --c++-kinds=+p --fields=+iaS --extra=+q ..
hsvtorgb: hsvtorgb.cpp
	$(CXX) -std=c++20 -g $^ -o $@

testerror: itjugglucotext.o nljugglucotext.o jugglucotext.o testerror.o 
	$(CXX)  -std=c++20 -g $^ -o $@

CircularArray:CircularArray.cpp CircularArray.hpp
	$(CXX)  ${CXXFLAGS} CircularArray.cpp -o $@
