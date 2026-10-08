/* R10 RED fixture: formato/args desalinhados = DEVE falhar em -Wformat=2 -Werror=format */
#include <stdio.h>
int main(void) {
    const char * s = "x";
    int n = 3;
    /*%d com const char* e %s com int: desalinhado de proposito*/
    printf("%d %s\n", s, n);
    return 0;
}
