from pathlib import Path
import subprocess,os
OUT=Path(__file__).resolve().parent
BASE=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
s=(OUT/'refs.cpp').read_text()
extra='''    block_q8_K qa[128]; float adq[256*128]; double rq8[8192];
    for(int b=0;b<B;b++) { quantize_row_q8_K_ref(act+(size_t)b*K,qa+b,K); dequantize_row_q8_K(qa+b,adq+(size_t)b*K,K); }
    double qerr=0,qden=0,qmax=0; long qviol=0;
    for(int b=0;b<B;b++) for(int n=0;n<N;n++) {
        size_t ix=(size_t)b*N+n;double v=0;
        for(int k=0;k<K;k++)v+=(double)dc[(size_t)n*K+k]*(double)adq[(size_t)b*K+k];
        rq8[ix]=v;double d=fabs((double)cpuout[ix]-v);qerr+=d*d;qden+=v*v;if(d>qmax)qmax=d;
        if(d>0.02+0.05*fabs(v))qviol++;
    }
    printf("CPU_vs_CANONICAL_Q8K_ACT: nmse=%.12e max_abs=%.12e legacy_viol=%ld\\n",qerr/qden,qmax,qviol);
    printf("Q8K_ACT coords (10,22) CPU=%.9f reference=%.9f ; (42,45) CPU=%.9f reference=%.9f\\n",cpuout[10*64+22],rq8[10*64+22],cpuout[42*64+45],rq8[42*64+45]);
'''
assert '    // a tabela dos violadores conhecidos' in s
s=s.replace('    // a tabela dos violadores conhecidos',extra+'    // a tabela dos violadores conhecidos')
(OUT/'refs-q8.cpp').write_text(s)
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
cmd=['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-ffunction-sections','-fdata-sections','-I'+(BASE/'include').as_posix(),'-I'+(BASE/'src').as_posix(),(OUT/'refs-q8.cpp').as_posix(),(OUT/'quants.o').as_posix(),'-Wl,--gc-sections','-o',(OUT/'refs-q8.exe').as_posix()]
r=subprocess.run(cmd,capture_output=True,text=True,env=env);(OUT/'q8-build.log').write_text(r.stdout+r.stderr);print('Q8_BUILD_EXIT',r.returncode);assert r.returncode==0,r.stderr[-1500:]
r=subprocess.run([(OUT/'refs-q8.exe').as_posix()],capture_output=True,text=True,env=env);(OUT/'q8-run.log').write_text(r.stdout+r.stderr);print('Q8_RUN_EXIT',r.returncode);print('\n'.join(l for l in r.stdout.splitlines() if 'Q8K_ACT' in l or 'Q8K_ACT coords' in l));assert r.returncode==0
