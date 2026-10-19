#pragma once
#include "config.h"
inline bool nearby(float dx,float dy,float density) {
	
	const float gr= density*
#ifdef WEAROS
	22;
#else
	24;
#endif
	const float grens=gr*gr;
	float afst=dx*dx+dy*dy;
	return afst<grens;
	}	
