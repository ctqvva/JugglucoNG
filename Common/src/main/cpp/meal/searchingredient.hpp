#include <array>
#include <regex>
#include "nummer.hpp"
#include"ingredient_t.h"



#ifdef TEST
#define LOGGER printf
#endif
//    const int nr=meals->datameal()->ingredientnr;
 //   const ingredients_t &ingr= getingredients();

bool reg_ingredient(const char *match,std::vector<int> &vect,ingredient_t *ingr,int len) {
    try {
        std::regex zoek(match, std::regex_constants::icase);
        std::copy_if(nummer(0),nummer(len), std::back_inserter(vect), [&zoek,&ingr](int el){return std::regex_search(ingr[el].name.data(),zoek);} );
        return true;
        }
    catch (const std::regex_error& e) {
        LOGGER( "exception std::regex %s\n", e.what());
        return false;
        }
    }
#ifdef TEST
#include <stdio.h>
#include <iostream>
int main(int argc,char **argv) {
    if(argc<2) {
       printf("Usage %s searchword\n",argv[0]);
       return 1;
        }
    ingredients_t ingr{{
    {1,2,2.5,"Name1"},
    {1,2,2.5,"other"},
    {1,2,2.5,"Name2"},
    {1,2,2.5,"niets2"},
    {1,2,2.5,"Name3"}}};
    const char *match=argv[1];
    int first =0,last=ingr.size();
    std::vector<int> vect;

    if(reg_ingredient(match,vect,ingr.data(),last)) {
        for(auto el:vect) {
            std::cout<<el<<": "<<ingr[el].name.data()<<std::endl;
            }
        }
}
#endif
