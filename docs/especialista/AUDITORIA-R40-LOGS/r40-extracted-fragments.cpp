#include <cstdio>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <string>
#include <atomic>
#include <direct.h>
#include <cerrno>
static int fake_getpid() { return 4242; }
#define getpid fake_getpid
static void rep(const char * fmt, ...) { va_list ap; va_start(ap,fmt); vprintf(fmt,ap); va_end(ap); puts(""); }
static int host_mkdir(const char * p,int) { int rc=_mkdir(p); printf("HOST_MKDIR rc=%d errno=%d\n",rc,errno); return rc; }
#define mkdir host_mkdir
static std::string next_id() {
                static std::atomic<long> g_attempt_seq{0};
                char attempt39[64];
                snprintf(attempt39, sizeof(attempt39), "r40_%ld_%d", (long) ++g_attempt_seq, (int) getpid());

 return attempt39;
}
int main(int argc,char **argv) {
 if (argc<2) return 2;
 if (strcmp(argv[1],"--ids")==0) { int n=argc>2?atoi(argv[2]):1; for(int i=0;i<n;i++)puts(next_id().c_str()); return 0; }
 if (strcmp(argv[1],"--mkdir")==0) { const char *dir39=argv[2];
(void) mkdir(dir39, 0770);
 puts("AFTER_ORIGINAL_MKDIR_REACHED"); return 0; }
 const char *exp_dir=argv[1];const char *attempt39="audit-attempt";
 int exfail=argc>2?atoi(argv[2]):0;
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
                        if (okc == 5) rep("R40 SNAPSHOT_COMPLETE attempt=%s (5/5 componentes!)", attempt39);
                        else rep("R40 SNAPSHOT_FAIL attempt=%s (%d/5 componentes!)", attempt39, okc);
                    }

 printf("HARNESS_AFTER exfail=%d\n",exfail); return 0;
}
