struct Context {
    struct Carbs {
            uint8_t carbFlags;
            uint16_t carbInfo;
            }  __attribute__ ((packed)) ;
    struct Rest {
            Carbs carb;
            uint8_t mealType;
            }__attribute__ ((packed)) ;
    bool hasCarbInfo:1;
    bool hasMealType:1;
    uint8_t mid:5;
    bool hasSecondaryFlags:1;
    uint16_t index;
    union {
        struct {
            uint8_t secFlags;
            Rest rest1;
            } __attribute__ ((packed)) ;
        Rest rest2;
        uint8_t mealType;
        };
    } __attribute__ ((packed)) ;

