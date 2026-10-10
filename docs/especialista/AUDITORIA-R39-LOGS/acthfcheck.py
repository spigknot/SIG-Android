from pathlib import Path
import subprocess,os
O=Path(__file__).resolve().parent;G=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
s=(O/'refs.cpp').read_text()
extra=r'''    float ahf[6144];
    for(int g=0;g<192;g++) {
      float mx=0;for(int j=0;j<32;j++)if(fabsf(act[g*32+j])>mx)mx=fabsf(act[g*32+j]);
      const float mh=GGML_FP16_TO_FP32(GGML_FP32_TO_FP16(mx));
      const float c=GGML_FP16_TO_FP32((ggml_half)0x2008);
      const float d=GGML_FP16_TO_FP32(GGML_FP32_TO_FP16(mh*c));
      const float inv=GGML_FP16_TO_FP32(GGML_FP32_TO_FP16(1.0f/d));
      for(int j=0;j<32;j++) {
        float x=GGML_FP16_TO_FP32(GGML_FP32_TO_FP16(act[g*32+j]));
        float y=GGML_FP16_TO_FP32(GGML_FP32_TO_FP16(x*inv));
        float q=nearbyintf(y);if(q>127)q=127;if(q< -128)q=-128;
        ahf[g*32+j]=q*d;
      }
    }
    for(int wm=0;wm<2;wm++) {
      double err=0,den=0,mx=0;long viol=0;
      for(int n=0;n<N;n++) {
        double v=0;for(int k=0;k<K;k++)v+=(wm?(double)leitor_q6k_w(tiled,K/32,n,k):(double)dc[(size_t)n*K+k])*(double)ahf[k];
        double d=fabs((double)htpout[n]-v);err+=d*d;den+=v*v;if(d>mx)mx=d;if(d>0.02+0.05*fabs(v))viol++;
      }
      printf("HTP_HF_Q8_ACT_MODEL weight=%s nmse=%.12e max_abs=%.12e legacy_viol=%ld\n",wm?"TILED":"CANON",err/den,mx,viol);
    }
'''
marker='    // as refs double (2048 saidas!)';assert marker in s
s=s.replace(marker,extra+marker,1);(O/'refs-acthf.cpp').write_text(s)
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
p=subprocess.run(['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-ffunction-sections','-fdata-sections','-I'+(G/'include').as_posix(),'-I'+(G/'src').as_posix(),(O/'refs-acthf.cpp').as_posix(),(O/'quants.o').as_posix(),'-Wl,--gc-sections','-o',(O/'refs-acthf.exe').as_posix()],capture_output=True,text=True,env=env)
(O/'acthf-build.log').write_text(p.stdout+p.stderr);print('BUILD_EXIT',p.returncode);assert p.returncode==0,p.stderr[-1200:]
p=subprocess.run([(O/'refs-acthf.exe').as_posix()],capture_output=True,text=True,env=env);(O/'acthf-run.log').write_text(p.stdout+p.stderr);print('RUN_EXIT',p.returncode);assert p.returncode==0
print('\n'.join(l for l in p.stdout.splitlines() if 'HTP_HF_Q8_ACT_MODEL' in l))
print('SCOPE: FP16/Q8 mathematical hypothesis informed by upstream hvx-quant.h; reciprocal is scalar HF rounding, not a captured SDK HVX output.')
