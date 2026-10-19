#pragma once
#include "nums/numdata.hpp"
#include "JCurve.hpp"
typedef struct NVGcontext NVGcontext;
class NumDisplay: public Numdata {
	public:
	using Numdata::Numdata;
//	pair<const Num*,const Num*> extrum;
	pair<int,int> extremenums(JCurve &j,const pair<const int,const int> extr) const; 
	template <class TX,class TY> void showNums(JCurve&jcurve, const TX &transx,  const TY &transy,bool *was) const ;
	const Num *findpast(const Num *high) const ;
    template <int N> const Num * findpast(JCurve &j) const;
	template <int N=1> const Num *findforward(JCurve &j) const ;
	static NumDisplay* getnumdisplay(int index, string_view base,identtype ident,size_t len) ; 
    template <class TX,class TY>  const Num *getnearby(JCurve &jcurve,const TX &transx,  const TY &transy,const float tapx,const float tapy) const ;
        private:
	static void standout(JCurve&jcurve,float x,float y) ; 
	void	mealdisplay( JCurve&jcurve,float x,float y,const Num *num) const ;
	};
