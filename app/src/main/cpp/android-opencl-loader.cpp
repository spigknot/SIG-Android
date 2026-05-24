#ifndef CL_TARGET_OPENCL_VERSION
#define CL_TARGET_OPENCL_VERSION 300
#endif

#include <CL/cl.h>
#include <CL/cl_function_types.h>
#include <dlfcn.h>

#include <mutex>
#include <string>
#include <utility>

extern "C" const char * sig_opencl_loader_last_error();

namespace {

std::string g_opencl_loader_error = "OpenCL loader ainda não foi acionado.";

void set_opencl_loader_error(std::string value) {
    g_opencl_loader_error = std::move(value);
}

void * opencl_handle() {
    static std::once_flag once;
    static void * handle = nullptr;
    std::call_once(once, [] {
        const char * candidates[] = {
            "libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib64/libOpenCL_adreno.so",
            "/vendor/lib/libOpenCL.so",
            "/vendor/lib/libOpenCL_adreno.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL_adreno.so",
            "/system/vendor/lib/libOpenCL.so",
            "/system/vendor/lib/libOpenCL_adreno.so",
            "/odm/lib64/libOpenCL.so",
            "/odm/lib/libOpenCL.so",
            "/product/lib64/libOpenCL.so",
            "/product/lib/libOpenCL.so",
            "/vendor/lib64/egl/libGLES_mali.so",
            "/vendor/lib/egl/libGLES_mali.so",
            "/system/vendor/lib64/egl/libGLES_mali.so",
            "/system/vendor/lib/egl/libGLES_mali.so",
        };
        std::string report;
        for (const char * candidate : candidates) {
            dlerror();
            handle = dlopen(candidate, RTLD_NOW | RTLD_LOCAL);
            if (handle != nullptr) {
                set_opencl_loader_error(std::string("OpenCL carregado de: ") + candidate);
                return;
            }
            const char * error = dlerror();
            report += std::string(candidate) + ": " + (error != nullptr ? error : "falha desconhecida") + "\n";
        }
        set_opencl_loader_error("Não consegui carregar nenhuma biblioteca OpenCL.\n" + report);
    });
    return handle;
}

template <typename Fn>
Fn load_opencl_fn(const char * name) {
    void * handle = opencl_handle();
    if (handle == nullptr) return nullptr;
    dlerror();
    void * symbol = dlsym(handle, name);
    if (symbol == nullptr) {
        const char * error = dlerror();
        set_opencl_loader_error(std::string("OpenCL carregou, mas faltou símbolo ") +
                                name + ": " + (error != nullptr ? error : "falha desconhecida"));
    }
    return reinterpret_cast<Fn>(symbol);
}

} // namespace

extern "C" const char * sig_opencl_loader_last_error() {
    return g_opencl_loader_error.c_str();
}

#define OPENCL_FN(name) load_opencl_fn<name##_fn>(#name)
#define OPENCL_MISSING_ERROR CL_INVALID_OPERATION
#define OPENCL_PLATFORM_NOT_FOUND -1001

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(
        cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms) {
    auto fn = OPENCL_FN(clGetPlatformIDs);
    return fn ? fn(num_entries, platforms, num_platforms) : OPENCL_PLATFORM_NOT_FOUND;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetPlatformInfo(
        cl_platform_id platform, cl_platform_info param_name, size_t param_value_size,
        void * param_value, size_t * param_value_size_ret) {
    auto fn = OPENCL_FN(clGetPlatformInfo);
    return fn ? fn(platform, param_name, param_value_size, param_value, param_value_size_ret) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetDeviceIDs(
        cl_platform_id platform, cl_device_type device_type, cl_uint num_entries,
        cl_device_id * devices, cl_uint * num_devices) {
    auto fn = OPENCL_FN(clGetDeviceIDs);
    return fn ? fn(platform, device_type, num_entries, devices, num_devices) : CL_DEVICE_NOT_FOUND;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetDeviceInfo(
        cl_device_id device, cl_device_info param_name, size_t param_value_size,
        void * param_value, size_t * param_value_size_ret) {
    auto fn = OPENCL_FN(clGetDeviceInfo);
    return fn ? fn(device, param_name, param_value_size, param_value, param_value_size_ret) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_context CL_API_CALL clCreateContext(
        const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices,
        void (CL_CALLBACK * pfn_notify)(const char *, const void *, size_t, void *),
        void * user_data, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateContext);
    if (!fn) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fn(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clReleaseContext(cl_context context) {
    auto fn = OPENCL_FN(clReleaseContext);
    return fn ? fn(context) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_command_queue CL_API_CALL clCreateCommandQueue(
        cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateCommandQueue);
    if (!fn) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fn(context, device, properties, errcode_ret);
}

extern "C" CL_API_ENTRY cl_mem CL_API_CALL clCreateBuffer(
        cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateBuffer);
    if (!fn) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fn(context, flags, size, host_ptr, errcode_ret);
}

extern "C" CL_API_ENTRY cl_mem CL_API_CALL clCreateBufferWithProperties(
        cl_context context, const cl_mem_properties * properties, cl_mem_flags flags,
        size_t size, void * host_ptr, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateBufferWithProperties);
    if (fn) return fn(context, properties, flags, size, host_ptr, errcode_ret);
    auto fallback = OPENCL_FN(clCreateBuffer);
    if (!fallback) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fallback(context, flags, size, host_ptr, errcode_ret);
}

extern "C" CL_API_ENTRY cl_mem CL_API_CALL clCreateSubBuffer(
        cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type,
        const void * buffer_create_info, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateSubBuffer);
    if (!fn) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fn(buffer, flags, buffer_create_type, buffer_create_info, errcode_ret);
}

extern "C" CL_API_ENTRY cl_mem CL_API_CALL clCreateImage(
        cl_context context, cl_mem_flags flags, const cl_image_format * image_format,
        const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateImage);
    if (!fn) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fn(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clReleaseMemObject(cl_mem memobj) {
    auto fn = OPENCL_FN(clReleaseMemObject);
    return fn ? fn(memobj) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithSource(
        cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateProgramWithSource);
    if (!fn) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fn(context, count, strings, lengths, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clBuildProgram(
        cl_program program, cl_uint num_devices, const cl_device_id * device_list,
        const char * options, void (CL_CALLBACK * pfn_notify)(cl_program, void *), void * user_data) {
    auto fn = OPENCL_FN(clBuildProgram);
    return fn ? fn(program, num_devices, device_list, options, pfn_notify, user_data) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetProgramBuildInfo(
        cl_program program, cl_device_id device, cl_program_build_info param_name,
        size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    auto fn = OPENCL_FN(clGetProgramBuildInfo);
    return fn ? fn(program, device, param_name, param_value_size, param_value, param_value_size_ret) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clReleaseProgram(cl_program program) {
    auto fn = OPENCL_FN(clReleaseProgram);
    return fn ? fn(program) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_kernel CL_API_CALL clCreateKernel(
        cl_program program, const char * kernel_name, cl_int * errcode_ret) {
    auto fn = OPENCL_FN(clCreateKernel);
    if (!fn) {
        if (errcode_ret) *errcode_ret = OPENCL_MISSING_ERROR;
        return nullptr;
    }
    return fn(program, kernel_name, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clSetKernelArg(
        cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value) {
    auto fn = OPENCL_FN(clSetKernelArg);
    return fn ? fn(kernel, arg_index, arg_size, arg_value) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetKernelInfo(
        cl_kernel kernel, cl_kernel_info param_name, size_t param_value_size,
        void * param_value, size_t * param_value_size_ret) {
    auto fn = OPENCL_FN(clGetKernelInfo);
    return fn ? fn(kernel, param_name, param_value_size, param_value, param_value_size_ret) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetKernelWorkGroupInfo(
        cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name,
        size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    auto fn = OPENCL_FN(clGetKernelWorkGroupInfo);
    return fn ? fn(kernel, device, param_name, param_value_size, param_value, param_value_size_ret) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetKernelSubGroupInfo(
        cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name,
        size_t input_value_size, const void * input_value, size_t param_value_size,
        void * param_value, size_t * param_value_size_ret) {
    auto fn = OPENCL_FN(clGetKernelSubGroupInfo);
    return fn ? fn(kernel, device, param_name, input_value_size, input_value, param_value_size, param_value, param_value_size_ret) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clEnqueueNDRangeKernel(
        cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim,
        const size_t * global_work_offset, const size_t * global_work_size,
        const size_t * local_work_size, cl_uint num_events_in_wait_list,
        const cl_event * event_wait_list, cl_event * event) {
    auto fn = OPENCL_FN(clEnqueueNDRangeKernel);
    return fn ? fn(command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size, num_events_in_wait_list, event_wait_list, event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clEnqueueReadBuffer(
        cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read,
        size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list,
        const cl_event * event_wait_list, cl_event * event) {
    auto fn = OPENCL_FN(clEnqueueReadBuffer);
    return fn ? fn(command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list, event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clEnqueueWriteBuffer(
        cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write,
        size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list,
        const cl_event * event_wait_list, cl_event * event) {
    auto fn = OPENCL_FN(clEnqueueWriteBuffer);
    return fn ? fn(command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list, event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clEnqueueCopyBuffer(
        cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer,
        size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list,
        const cl_event * event_wait_list, cl_event * event) {
    auto fn = OPENCL_FN(clEnqueueCopyBuffer);
    return fn ? fn(command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size, num_events_in_wait_list, event_wait_list, event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clEnqueueFillBuffer(
        cl_command_queue command_queue, cl_mem buffer, const void * pattern,
        size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list,
        const cl_event * event_wait_list, cl_event * event) {
    auto fn = OPENCL_FN(clEnqueueFillBuffer);
    return fn ? fn(command_queue, buffer, pattern, pattern_size, offset, size, num_events_in_wait_list, event_wait_list, event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clEnqueueBarrierWithWaitList(
        cl_command_queue command_queue, cl_uint num_events_in_wait_list,
        const cl_event * event_wait_list, cl_event * event) {
    auto fn = OPENCL_FN(clEnqueueBarrierWithWaitList);
    return fn ? fn(command_queue, num_events_in_wait_list, event_wait_list, event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clEnqueueMarkerWithWaitList(
        cl_command_queue command_queue, cl_uint num_events_in_wait_list,
        const cl_event * event_wait_list, cl_event * event) {
    auto fn = OPENCL_FN(clEnqueueMarkerWithWaitList);
    return fn ? fn(command_queue, num_events_in_wait_list, event_wait_list, event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clFlush(cl_command_queue command_queue) {
    auto fn = OPENCL_FN(clFlush);
    return fn ? fn(command_queue) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clFinish(cl_command_queue command_queue) {
    auto fn = OPENCL_FN(clFinish);
    return fn ? fn(command_queue) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clWaitForEvents(cl_uint num_events, const cl_event * event_list) {
    auto fn = OPENCL_FN(clWaitForEvents);
    return fn ? fn(num_events, event_list) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clReleaseEvent(cl_event event) {
    auto fn = OPENCL_FN(clReleaseEvent);
    return fn ? fn(event) : OPENCL_MISSING_ERROR;
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL clGetEventProfilingInfo(
        cl_event event, cl_profiling_info param_name, size_t param_value_size,
        void * param_value, size_t * param_value_size_ret) {
    auto fn = OPENCL_FN(clGetEventProfilingInfo);
    return fn ? fn(event, param_name, param_value_size, param_value, param_value_size_ret) : OPENCL_MISSING_ERROR;
}
