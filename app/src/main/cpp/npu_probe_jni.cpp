#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr const char * kTag = "SIGNpuProbe";

struct LibraryProbe {
    std::string name;
    bool loaded = false;
    bool provider_symbol = false;
    std::string error;
};

std::vector<LibraryProbe> probe_libraries() {
    const char * libraries[] = {
        "libQnnSystem.so",
        "libQnnHtp.so",
        "libQnnHtpV79Stub.so",
        "libQnnHtpV75Stub.so",
        "libQnnHtpV73Stub.so",
        "libQnnHtpV69Stub.so",
        "libQnnHtpV68Stub.so"
    };

    std::vector<LibraryProbe> result;
    for (const char * name : libraries) {
        LibraryProbe item;
        item.name = name;
        dlerror();
        void * handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            const char * error = dlerror();
            item.error = error == nullptr ? "falha desconhecida" : error;
        } else {
            item.loaded = true;
            item.provider_symbol = dlsym(handle, "QnnInterface_getProviders") != nullptr ||
                                   dlsym(handle, "QnnSystemInterface_getProviders") != nullptr;
            dlclose(handle);
        }
        result.push_back(std::move(item));
    }
    return result;
}

std::string build_report() {
    const auto probes = probe_libraries();
    bool system_loaded = false;
    bool htp_loaded = false;
    bool provider_found = false;

    std::ostringstream report;
    report << "Diagnóstico nativo QAIRT/QNN\n";
    report << "Modo: detecção dinâmica; execução HTP não habilitada neste build\n";
    for (const auto & probe : probes) {
        report << probe.name << ": ";
        if (probe.loaded) {
            report << "carregada";
            if (probe.provider_symbol) report << "; provider exportado";
        } else {
            report << "indisponível; " << probe.error;
        }
        report << '\n';

        if (probe.name == "libQnnSystem.so" && probe.loaded) system_loaded = true;
        if (probe.name == "libQnnHtp.so" && probe.loaded) htp_loaded = true;
        provider_found = provider_found || probe.provider_symbol;
    }

    report << "Runtime QNN carregado: " << ((system_loaded || htp_loaded) ? "sim" : "não") << '\n';
    report << "Interface provider localizada: " << (provider_found ? "sim" : "não") << '\n';
    report << "Backend HTP inicializado: não\n";
    report << "Execução confirmada no HTP: não\n";
    report << "Motivo: o QAIRT SDK e um pacote de encoder compatível não estão integrados ao build.";
    return report.str();
}

jstring to_jstring(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_experimental_npu_NpuNativeProbe_diagnose(
        JNIEnv * env, jobject) {
    try {
        const std::string report = build_report();
        __android_log_print(ANDROID_LOG_INFO, kTag, "%s", report.c_str());
        return to_jstring(env, report);
    } catch (const std::exception & error) {
        return to_jstring(env, std::string("Falha no diagnóstico nativo: ") + error.what());
    } catch (...) {
        return to_jstring(env, "Falha desconhecida no diagnóstico nativo.");
    }
}
