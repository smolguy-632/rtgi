package com.rtgi.client;

import org.jocl.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.jocl.CL.*;

/** OpenCL host side (JOCL). Used from ONE worker thread only. */
public final class GIEngine {
    public final String deviceName;
    private final cl_context ctx;
    private final cl_command_queue queue;
    private final cl_program program;
    private final cl_kernel kernel;

    private final List<cl_mem> allocs = new ArrayList<cl_mem>();

    private GIEngine(cl_platform_id plat, cl_device_id dev, String name) throws Exception {
        this.deviceName = name;
        cl_context_properties props = new cl_context_properties();
        props.addProperty(CL_CONTEXT_PLATFORM, plat);
        ctx = clCreateContext(props, 1, new cl_device_id[]{dev}, null, null, null);
        queue = clCreateCommandQueue(ctx, dev, 0, null);
        program = clCreateProgramWithSource(ctx, 1, new String[]{readKernel()}, null, null);
        clBuildProgram(program, 0, null, null, null, null);
        kernel = clCreateKernel(program, "render", null);
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

    private cl_mem buf(Object src, long bytes, long flags) {
        cl_mem m = clCreateBuffer(ctx, flags, Math.max(bytes, 4), null, null);
        allocs.add(m);
        if (src != null) {
            Pointer p;
            if (src instanceof long[]) p = Pointer.to((long[]) src);
            else if (src instanceof int[]) p = Pointer.to((int[]) src);
            else if (src instanceof short[]) p = Pointer.to((short[]) src);
            else if (src instanceof float[]) p = Pointer.to((float[]) src);
            else if (src instanceof byte[]) p = Pointer.to((byte[]) src);
            else throw new IllegalArgumentException();
            clEnqueueWriteBuffer(queue, m, true, 0, bytes, p, 0, null, null);
        }
        return m;
    }

    public static final class Result { public int w, h; public int[] color; public float[] depth; }

    /** Run the full-scene RT kernel. */
    public Result run(Tree64 t, VoxelVolume vol, BlockPalette pal, int[] colTint,
                     int[] emHead, int[] emNext, float[] emPos, int emitterCount,
                     int[] atlas, int atlasW, int atlasH,
                     float[] P, int[] IP, int outW, int outH) throws Exception {
        // release previous
        for (cl_mem m : allocs) try { clReleaseMemObject(m); } catch (Throwable ignore) {}
        allocs.clear();

        long nodeCount = t.nodeCount;
        cl_mem bMasks = buf(t.masks, 8L * nodeCount, CL_MEM_READ_ONLY);
        cl_mem bPtrs = buf(t.ptrs, 4L * nodeCount, CL_MEM_READ_ONLY);
        cl_mem bMats = buf(t.mats, 2L * t.mats.length, CL_MEM_READ_ONLY);
        cl_mem bPalFlags = buf(pal.flags, 4L * (pal.maxId() + 1), CL_MEM_READ_ONLY);
        cl_mem bPalUV = buf(pal.uv, 4L * (pal.maxId() + 1) * 24, CL_MEM_READ_ONLY);
        cl_mem bPalTint = buf(pal.tint, 4L * (pal.maxId() + 1) * 6, CL_MEM_READ_ONLY);
        cl_mem bPalBox = buf(pal.box, 4L * (pal.maxId() + 1) * 8, CL_MEM_READ_ONLY);
        cl_mem bLight = buf(vol.light, vol.light.length, CL_MEM_READ_ONLY);
        cl_mem bColTint = buf(colTint, 4L * colTint.length, CL_MEM_READ_ONLY);
        cl_mem bEmHead = buf(emHead, 4L * emHead.length, CL_MEM_READ_ONLY);
        cl_mem bEmNext = buf(emNext, 4L * emNext.length, CL_MEM_READ_ONLY);
        cl_mem bEmPos = buf(emPos, 4L * Math.max(1, emitterCount * 4), CL_MEM_READ_ONLY);
        cl_mem bAtlas = buf(atlas, 4L * atlas.length, CL_MEM_READ_ONLY);
        cl_mem bP = buf(P, 4L * P.length, CL_MEM_READ_ONLY);
        cl_mem bIP = buf(IP, 4L * IP.length, CL_MEM_READ_ONLY);
        int[] outColor = new int[outW * outH];
        float[] outDepth = new float[outW * outH];
        cl_mem bOutColor = buf(null, 4L * outColor.length, CL_MEM_WRITE_ONLY);
        cl_mem bOutDepth = buf(null, 4L * outDepth.length, CL_MEM_WRITE_ONLY);

        int a = 0;
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bMasks));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bPtrs));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bMats));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bPalFlags));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bPalUV));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bPalTint));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bPalBox));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bLight));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bColTint));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bEmHead));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bEmNext));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bEmPos));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bAtlas));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bP));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bIP));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bOutColor));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(bOutDepth));

        clEnqueueNDRangeKernel(queue, kernel, 1, null, new long[]{(long) outW * outH}, null, 0, null, null);
        java.nio.ByteBuffer cbuf = org.lwjgl.BufferUtils.createByteBuffer(outColor.length * 4);
        java.nio.ByteBuffer dbuf = org.lwjgl.BufferUtils.createByteBuffer(outDepth.length * 4);
        clEnqueueReadBuffer(queue, bOutColor, true, 0, 4L * outColor.length, Pointer.to(cbuf), 0, null, null);
        clEnqueueReadBuffer(queue, bOutDepth, true, 0, 4L * outDepth.length, Pointer.to(dbuf), 0, null, null);
        cbuf.flip(); dbuf.flip();
        java.nio.IntBuffer ci = cbuf.asIntBuffer();
        java.nio.FloatBuffer di = dbuf.asFloatBuffer();
        for (int i = 0; i < outColor.length; i++) outColor[i] = ci.get(i);
        for (int i = 0; i < outDepth.length; i++) outDepth[i] = di.get(i);

        Result r = new Result();
        r.w = outW; r.h = outH; r.color = outColor; r.depth = outDepth;
        return r;
    }
}