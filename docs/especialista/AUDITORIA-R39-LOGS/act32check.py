from pathlib import Path
import subprocess,os,re
O=Path(__file__).resolve().parent;G=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
s=(O/'refs.cpp').read_text()
marker='    // as refs double (2048 saidas!)'
assert marker in s
extra=r'''    block_q8_0 a0[192];float a16[6144],a32[6144];
    quantize_row_q8_0_ref(act,a0,K); dequantize_row_q8_0(a0,a16,K);
    for(int g=0;g<192;g++) {
       float mx=0;for(int j=0;j<32;j++)if(fabsf(act[g*32+j])>mx)mx=fabsf(act[g*32+j]);
       const float d=mx/127.0f;for(int j=0;j<32;j++)a32[g*32+j]=(float)a0[g].qs[j]*d;
    }
    const float* amodels[2]={a16,a32};
    for(int am=0;am<2;am++)for(int wm=0;wm<2;wm++) {
       double err=0,den=0,mx=0;long viol=0;
       for(int n=0;n<N;n++) {
          double v=0;for(int k=0;k<K;k++)v+=(wm? (double)leitor_q6k_w(tiled,K/32,n,k):(double)dc[(size_t)n*K+k])*(double)amodels[am][k];
          double d=fabs((double)htpout[n]-v);err+=d*d;den+=v*v;if(d>mx)mx=d;if(d>0.02+0.05*fabs(v))viol++;
       }
       printf("HTP_ACT32_MODEL scale=%s weight=%s nmse=%.12e max_abs=%.12e legacy_viol=%ld\n",am?"F32":"F16",wm?"TILED":"CANON",err/den,mx,viol);
    }
'''
s=s.replace(marker,extra+marker,1);(O/'refs-act32.cpp').write_text(s)
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
p=subprocess.run(['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-ffunction-sections','-fdata-sections','-I'+(G/'include').as_posix(),'-I'+(G/'src').as_posix(),(O/'refs-act32.cpp').as_posix(),(O/'quants.o').as_posix(),'-Wl,--gc-sections','-o',(O/'refs-act32.exe').as_posix()],capture_output=True,text=True,env=env)
(O/'act32-build.log').write_text(p.stdout+p.stderr);print('BUILD_EXIT',p.returncode);assert p.returncode==0,p.stderr[-1200:]
p=subprocess.run([(O/'refs-act32.exe').as_posix()],capture_output=True,text=True,env=env);(O/'act32-run.log').write_text(p.stdout+p.stderr);print('RUN_EXIT',p.returncode);assert p.returncode==0
print('\n'.join(l for l in p.stdout.splitlines() if 'HTP_ACT32_MODEL' in l))
print('SCOPE: tested mathematical activation models; not dump of packed DSP activations.')
