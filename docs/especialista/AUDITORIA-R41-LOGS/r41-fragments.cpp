#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <direct.h>
static const char *parent_dir;


static void rep(const char* f,...) {va_list ap;va_start(ap,f);vprintf(f,ap);va_end(ap);puts("");}
static int host_mkdir(const char *p,int) {return _mkdir(strstr(p,"/data/data/")==p?parent_dir:p);}
static FILE *host_fopen(const char *p,const char *m) {char b[1024];if(strstr(p,"/data/data/")==p){snprintf(b,sizeof(b),"%s/sel.txt",parent_dir);return fopen(b,m);}return fopen(p,m);}
#define mkdir host_mkdir
#define fopen host_fopen
int main(int argc,char**argv){if(argc<3)return 2;parent_dir=argv[2];const char*dir39=argv[2];const char*attempt39="new-attempt";
if(strcmp(argv[1],"setup")==0){int K=6144,N=2048,B=1;struct{const char*nm;}ty={"q6_K"};
                int mk41 = 0;
                if (mkdir("/data/data/br.gov.sp.pcsp.npuprobe/files/r39", 0770) != 0 && errno != EEXIST) mk41 = 1;
                if (mkdir(dir39, 0770) != 0) mk41 = 1;   // o dir do attempt TEM de ser novo!
                if (mk41) { rep("R41 SEL_FAIL (mkdir: %s!)", strerror(errno)); }
                {
                    FILE * fsel = fopen("/data/data/br.gov.sp.pcsp.npuprobe/files/r39/sel.txt", "w");
                    if (fsel) {
                        fprintf(fsel, "%s %d %d %d %s\n", attempt39, K, N, B, ty.nm);
                        fclose(fsel);
                    }
                    rep("R39 SEL: attempt=%s K=%d N=%d B=%d type=%s", attempt39, K, N, B, ty.nm);
                }

puts("AFTER_SETUP_REACHED");return 0;}
const char*exp_dir=argv[2];int exfail=argc>3?atoi(argv[3]):0;
                    {
                        const char * nomes[5] = { "canonweights_9216.bin", "actfloat_131072.bin",
                                                  "cpuout_32768.bin", "htpout_32768.bin", "tiled_raw.bin" };
                        int okc = 0;
                        for (int ii = 0; ii < 5; ii++) {
                            char pth[320];
                            snprintf(pth, sizeof(pth), "%s/%s", exp_dir, nomes[ii]);
                            FILE * fchk = fopen(pth, "rb");
                            if (fchk) {
                                fseek(fchk, 0, SEEK_END);
                                const long szc = ftell(fchk);
                                fclose(fchk);
                                if (szc > 0) okc++;
                            }
                        }
                        // R41: o META precisa existir/nao-vazio ANTES (fail-closed real!)
                        char meta41[320];
                        snprintf(meta41, sizeof(meta41), "%s/meta.txt", exp_dir);
                        FILE * fmc = fopen(meta41, "rb");
                        long msz = -1;
                        if (fmc) { fseek(fmc, 0, SEEK_END); msz = ftell(fmc); fclose(fmc); }
                        if (okc == 5 && msz > 0 && !exfail) rep("R41 SNAPSHOT_COMPLETE attempt=%s (5/5+meta! exfail=0!)", attempt39);
                        else rep("R41 SNAPSHOT_FAIL attempt=%s (%d/5 meta=%ld exfail=%d — agregado!)", attempt39, okc, msz, (int) exfail);
                    }

return 0;}
