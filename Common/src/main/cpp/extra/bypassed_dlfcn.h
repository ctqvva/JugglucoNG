//
// Created by windysha
//

#ifndef EASYBYPASSRESTRICTIONS_BYPASSED_DLFCN_H
#define EASYBYPASSRESTRICTIONS_BYPASSED_DLFCN_H

#ifdef __cplusplus
extern "C" {
#endif


void *bypass_loader_dlopen(const char *filename, int flag);


#ifdef __cplusplus
    }
#endif

#endif //EASYBYPASSRESTRICTIONS_BYPASSED_DLFCN_H
