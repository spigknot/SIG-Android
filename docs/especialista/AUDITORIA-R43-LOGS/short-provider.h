#include <stdio.h>
#include <string.h>
static FILE *audit_popen(const char *cmd,const char *mode){(void)cmd;(void)mode;FILE*f=tmpfile();if(!f)return NULL;fputs("ABCDEF\n",f);rewind(f);return f;}
static int audit_pclose(FILE*f){return fclose(f);}
#define popen audit_popen
#define pclose audit_pclose
