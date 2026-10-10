#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cerrno>
#include <direct.h>
static const char* base;static int fail=0;
static void rep(const char*f,...){va_list a;va_start(a,f);vprintf(f,a);va_end(a);puts("");}
static int mm(const char*p,int){return _mkdir(strstr(p,"/data/data/")==p?base:p);}
static FILE*ff(const char*p,const char*m){char b[1024];if(strstr(p,"/data/data/")==p){snprintf(b,sizeof(b),"%s/sel.txt",base);return fopen(b,m);}return fopen(p,m);}
#define mkdir mm
#define fopen ff
static void real_setup(){const char*dir39=base;const char*attempt39="new";int K=6144,N=2048,B=1;struct{const char*nm;}ty={"q6_K"};
                int mk41 = 0;
                if (mkdir("/data/data/br.gov.sp.pcsp.npuprobe/files/r39", 0770) != 0 && errno != EEXIST) mk41 = 1;
                if (mkdir(dir39, 0770) != 0) mk41 = 1;   // o dir do attempt TEM de ser novo!
                if (mk41) {
                    rep("R42 SEL_FAIL_ESTRUTURAL (mkdir: %s!)", strerror(errno));
                    fail++; return;   // RECUSA consumida: sem selector/upload/compute!
                }
                {
                    FILE * fsel = fopen("/data/data/br.gov.sp.pcsp.npuprobe/files/r39/sel.txt", "w");
                    if (fsel) {
                        fprintf(fsel, "%s %d %d %d %s\n", attempt39, K, N, B, ty.nm);
                        fclose(fsel);
                    }
                    rep("R39 SEL: attempt=%s K=%d N=%d B=%d type=%s", attempt39, K, N, B, ty.nm);
                }
puts("AFTER_SETUP");}
int main(int argc,char**argv){if(argc<2)return 2;base=argv[1];real_setup();printf("fail=%d\n",fail);return fail==1?0:1;}
