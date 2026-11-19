package com.rtgi.client;

import org.jocl.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.jocl.CL.*;

/** OpenCL host side (JOCL: works on Forge/LWJGL2 and Cleanroom/LWJGL3). Used from ONE worker thread only. */
public final class GIEngine {
    public final String deviceName;
    private final cl_context ctx;
    private final cl_command_queue queue;
    private final cl_program program;
    private final cl_kernel kernel;

    private final Buf bMasks, bPtrs, bMats, bPos, bNrm, bOut;

    private final class Buf {
        cl_mem mem; long cap; final long flags;
        Buf(long flags) { this.flags = flags; }
        void ensure(long need) {
            if (mem != null && need <= cap) return;
            if (mem != null) clReleaseMemObject(mem);
            long c = Math.max(need, 4096L); c += c >> 2;
            mem = clCreateBuffer(ctx, flags, c, null, null);
            cap = c;
        }
    }

    private GIEngine(cl_platform_id plat, cl_device_id dev, String name) throws Exception {
        this.deviceName = name;
        cl_context_properties props = new cl_context_properties();
        props.addProperty(CL_CONTEXT_PLATFORM, plat);
        ctx = clCreateContext(props, 1, new cl_device_id[]{dev}, null, null, null);
        queue = clCreateCommandQueue(ctx, dev, 0, null);
        program = clCreateProgramWithSource(ctx, 1, new String[]{readKernel()}, null, null);
        clBuildProgram(program, 0, null, null, null, null);
        kernel = clCreateKernel(program, "gi_tree64", null);
        bMasks = new Buf(CL_MEM_READ_ONLY); bPtrs = new Buf(CL_MEM_READ_ONLY); bMats = new Buf(CL_MEM_READ_ONLY);
        bPos = new Buf(CL_MEM_READ_ONLY); bNrm = new Buf(CL_MEM_READ_ONLY); bOut = new Buf(CL_MEM_WRITE_ONLY);
    }

    private static String readKernel() throws Exception {
        try (InputStream in = GIEngine.class.getResourceAsStream("/assets/rtgi/rtgi.cl")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return new String(bo.toByteArray(), "UTF-8");
        }
    }

    private static String info(cl_device_id d, int param) {
        long[] sz = new long[1];
        clGetDeviceInfo(d, param, 0, null, sz);
        byte[] b = new byte[(int) sz[0]];
        clGetDeviceInfo(d, param, b.length, Pointer.to(b), null);
        return new String(b, 0, Math.max(0, b.length - 1)).trim();
    }

    public static GIEngine create(int index, boolean skipD3D12) throws Exception {
        CL.setExceptionsEnabled(true);
        int[] np = new int[1];
        clGetPlatformIDs(0, null, np);
        cl_platform_id[] plats = new cl_platform_id[np[0]];
        clGetPlatformIDs(plats.length, plats, null);
        List<cl_platform_id> lp = new ArrayList<cl_platform_id>();
        List<cl_device_id> ld = new ArrayList<cl_device_id>();
        List<String> ln = new ArrayList<String>();
        for (cl_platform_id p : plats) {
            int[] nd = new int[1];
            try { clGetDeviceIDs(p, CL_DEVICE_TYPE_GPU, 0, null, nd); } catch (CLException e) { continue; }
            if (nd[0] == 0) continue;
            cl_device_id[] ds = new cl_device_id[nd[0]];
            clGetDeviceIDs(p, CL_DEVICE_TYPE_GPU, ds.length, ds, null);
            for (cl_device_id d : ds) {
                String vendor = info(d, CL_DEVICE_VENDOR).toLowerCase();
                String ver = info(d, CL_DEVICE_VERSION).toLowerCase();
                if (skipD3D12 && (vendor.contains("microsoft") || ver.contains("d3d12"))) continue;
                lp.add(p); ld.add(d); ln.add(info(d, CL_DEVICE_NAME));
            }
        }
        if (ld.isEmpty()) throw new IllegalStateException("No usable OpenCL GPU found");
        int i = index < 0 ? 0 : Math.min(index, ld.size() - 1);
        return new GIEngine(lp.get(i), ld.get(i), ln.get(i));
    }

    private void write(Buf b, Pointer p, long bytes) {
        b.ensure(bytes);
        clEnqueueWriteBuffer(queue, b.mem, true, 0, bytes, p, 0, null, null);
    }

    /** @return float4 per sample: rgb light, w = hit distance (-1 = sky). */
    public float[] run(Tree64 t, float[] pos, float[] nrm, int n,
                       float camx, float camy, float camz, float maxDist,
                       float skyr, float skyg, float skyb, float ambient) {
        write(bMasks, Pointer.to(t.masks), 8L * t.nodeCount);
        write(bPtrs, Pointer.to(t.ptrs), 4L * t.nodeCount);
        write(bMats, Pointer.to(t.mats), t.mats.length);
        write(bPos, Pointer.to(pos), 16L * n);
        write(bNrm, Pointer.to(nrm), 16L * n);
        bOut.ensure(16L * n);

        int a = 0;
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bMasks.mem));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bPtrs.mem));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bMats.mem));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bPos.mem));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bNrm.mem));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bOut.mem));
        clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{n}));
        float[] f = {camx, camy, camz, maxDist, skyr, skyg, skyb, ambient};
        for (float v : f) clSetKernelArg(kernel, a++, Sizeof.cl_float, Pointer.to(new float[]{v}));

        clEnqueueNDRangeKernel(queue, kernel, 1, null, new long[]{n}, null, 0, null, null);
        float[] out = new float[4 * n];
        clEnqueueReadBuffer(queue, bOut.mem, true, 0, 16L * n, Pointer.to(out), 0, null, null);
        return out;
    }
}
