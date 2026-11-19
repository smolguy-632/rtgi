package com.rtgi.client;

import com.rtgi.RTGIConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Schedules GI passes. The main thread only mirrors blocks into the voxel volume (time-sliced) and, every
 * N ticks, hands a job to a worker thread: build 64-tree -> pick 1 surface face per voxel -> OpenCL trace
 * (1 mirror ray each) -> build quad buffer. The render thread just uploads the finished buffer.
 */
public final class GIManager {
    public static final class Result { int originX, originZ, quadCount; ByteBuffer quads; }

    private static final class Params {
        int ox, oz, maxSamples, radiusChunks, device; boolean skipD3D12;
        float camx, camy, camz, maxDist, strength, sky, ambient;
    }
    private static final class Samples { float[] pos, nrm; int n; }

    private static final Logger LOG = LogManager.getLogger("RTGI");
    private static final int[][] N = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final VoxelVolume vol = new VoxelVolume();
    private final GIOverlay overlay = new GIOverlay();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<Result> pending = new AtomicReference<Result>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) { Thread t = new Thread(r, "RTGI-Worker"); t.setDaemon(true); return t; }
    });
    private final IWorldEventListener listener = new Listener();
    private GIEngine engine;
    private volatile boolean disabled;
    private int failures, tickCounter, stride = 1;
    private long jobs;
    private World lastWorld;

    // ------------------------------------------------------------------ events (main/render thread)
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load e) {
        if (e.getWorld().isRemote) e.getWorld().addEventListener(listener);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload e) {
        if (e.getWorld().isRemote) { e.getWorld().removeEventListener(listener); overlay.clear(); }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || disabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!RTGIConfig.enabled || mc.world == null || mc.player == null) return;
        if (mc.world != lastWorld) {
            lastWorld = mc.world; vol.reset(); overlay.clear(); pending.set(null); tickCounter = 0;
        }
        int pcx = MathHelper.floor(mc.player.posX) >> 4, pcz = MathHelper.floor(mc.player.posZ) >> 4;
        vol.recenter(pcx, pcz);
        vol.scan(mc.world, RTGIConfig.scanSectionsPerTick, pcx, pcz, RTGIConfig.radiusChunks);

        // GI passes only run every N ticks, and never overlap.
        if (++tickCounter >= RTGIConfig.refreshIntervalTicks && busy.compareAndSet(false, true)) {
            tickCounter = 0;
            final Params p = new Params();
            p.ox = vol.originX; p.oz = vol.originZ;
            Entity eye = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.player;
            p.camx = (float) (eye.posX - p.ox); p.camy = (float) (eye.posY + eye.getEyeHeight()); p.camz = (float) (eye.posZ - p.oz);
            p.maxSamples = RTGIConfig.maxSamples; p.radiusChunks = RTGIConfig.radiusChunks;
            p.device = RTGIConfig.openclDevice; p.skipD3D12 = RTGIConfig.skipD3D12Devices;
            p.maxDist = (float) RTGIConfig.maxRayDistance; p.strength = (float) RTGIConfig.strength;
            p.sky = (float) RTGIConfig.skyContribution; p.ambient = p.sky * 0.4f;
            pool.execute(new Runnable() { public void run() { runJob(p); } });
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!RTGIConfig.enabled || disabled) return;
        Result r = pending.getAndSet(null);
        if (r != null) overlay.upload(r);
        overlay.draw(e.getPartialTicks());
    }

    // ------------------------------------------------------------------ worker thread
    private void runJob(Params p) {
        try {
            if (engine == null) {
                engine = GIEngine.create(p.device, p.skipD3D12);
                LOG.info("RT GI using OpenCL device: {}", engine.deviceName);
            }
            long t0 = System.nanoTime();
            Tree64 tree = Tree64.build(vol, p.ox, p.oz);
            long t1 = System.nanoTime();
            Samples s = buildSamples(p);
            long t2 = System.nanoTime();
            float[] out = s.n == 0 ? new float[0]
                    : engine.run(tree, s.pos, s.nrm, s.n, p.camx, p.camy, p.camz, p.maxDist,
                                 0.5f * p.sky, 0.7f * p.sky, 1.0f * p.sky, p.ambient);
            long t3 = System.nanoTime();
            pending.set(buildQuads(p, s, out));
            failures = 0;
            if (RTGIConfig.debugLog && (++jobs % 100) == 1)
                LOG.info("pass: tree {} ms ({} nodes), samples {} ms ({} rays), gpu {} ms, quads {} ms",
                        (t1 - t0) / 1e6, tree.nodeCount, (t2 - t1) / 1e6, s.n, (t3 - t2) / 1e6, (System.nanoTime() - t3) / 1e6);
        } catch (Throwable t) {
            LOG.error("RT GI pass failed", t);
            if (engine == null || ++failures >= 3) { disabled = true; LOG.error("RT GI disabled for this session."); }
        } finally {
            busy.set(false);
        }
    }

    /** Exactly one sample per exposed solid voxel: the exposed face that points most toward the camera. */
    private Samples buildSamples(Params p) {
        int cap = Math.min(p.maxSamples, 1 << 16);
        float[] pos = new float[cap * 4], nrm = new float[cap * 4];
        int n = 0, total = 0, st = stride;
        float rad = p.radiusChunks * 16 + 16;
        byte[] cells = vol.cells;
        for (int sx = 0; sx < 16; sx++) for (int sz = 0; sz < 16; sz++) {
            float dx = sx * 16 + 8 - p.camx, dz = sz * 16 + 8 - p.camz;
            if (dx * dx + dz * dz > rad * rad) continue;
            for (int sy = 0; sy < 16; sy++) {
                if (vol.sectionCount[VoxelVolume.secIndex(p.ox + sx * 16, sy * 16, p.oz + sz * 16)] == 0) continue;
                for (int lx = 0; lx < 16; lx++) for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) {
                    int x = sx * 16 + lx, y = sy * 16 + ly, z = sz * 16 + lz;
                    if (cells[VoxelVolume.cellIndex(p.ox + x, y, p.oz + z)] != 1) continue;
                    float tx = p.camx - (x + 0.5f), ty = p.camy - (y + 0.5f), tz = p.camz - (z + 0.5f);
                    float inv = 1.0f / (float) (Math.sqrt(tx * tx + ty * ty + tz * tz) + 1e-6);
                    int bf = -1; float best = -9f;
                    for (int f = 0; f < 6; f++) {
                        int nx = x + N[f][0], ny = y + N[f][1], nz = z + N[f][2];
                        byte m2;
                        if (ny < 0) m2 = 1;
                        else if (ny > 255) m2 = 0;
                        else if (nx < 0 || nx > 255 || nz < 0 || nz > 255) m2 = 1;   // outside window counts as solid
                        else m2 = cells[VoxelVolume.cellIndex(p.ox + nx, ny, p.oz + nz)];
                        if (m2 == 1) continue;                                       // not exposed
                        float score = (N[f][0] * tx + N[f][1] * ty + N[f][2] * tz) * inv;
                        if (score > best) { best = score; bf = f; }
                    }
                    if (bf < 0) continue;
                    total++;
                    if (st > 1 && (((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) & 0x7fffffff) % st != 0) continue;
                    if (n >= p.maxSamples) continue;
                    if (n == cap) { cap = Math.min(cap * 2, p.maxSamples); pos = Arrays.copyOf(pos, cap * 4); nrm = Arrays.copyOf(nrm, cap * 4); }
                    int o = n * 4;
                    pos[o] = x + 0.5f + N[bf][0] * 0.51f; pos[o + 1] = y + 0.5f + N[bf][1] * 0.51f; pos[o + 2] = z + 0.5f + N[bf][2] * 0.51f;
                    nrm[o] = N[bf][0]; nrm[o + 1] = N[bf][1]; nrm[o + 2] = N[bf][2];
                    n++;
                }
            }
        }
        stride = Math.max(1, (total + p.maxSamples - 1) / p.maxSamples);
        Samples s = new Samples(); s.pos = pos; s.nrm = nrm; s.n = n;
        return s;
    }

    private Result buildQuads(Params p, Samples s, float[] out) {
        ByteBuffer bb = ByteBuffer.allocateDirect(Math.max(1, s.n) * 64).order(ByteOrder.nativeOrder());
        int q = 0;
        for (int i = 0; i < s.n; i++) {
            float r = out[i * 4] * p.strength, g = out[i * 4 + 1] * p.strength, b = out[i * 4 + 2] * p.strength;
            float mx = Math.max(r, Math.max(g, b));
            if (mx < 0.004f) continue;
            if (mx > 1f) { r /= mx; g /= mx; b /= mx; }
            byte rb = (byte) (int) (r * 255f + 0.5f), gb = (byte) (int) (g * 255f + 0.5f), bbyte = (byte) (int) (b * 255f + 0.5f);
            float nx = s.nrm[i * 4], ny = s.nrm[i * 4 + 1], nz = s.nrm[i * 4 + 2];
            float cx = s.pos[i * 4] - nx * 0.507f, cy = s.pos[i * 4 + 1] - ny * 0.507f, cz = s.pos[i * 4 + 2] - nz * 0.507f;
            float ux, uy, uz, vx, vy, vz;
            if (Math.abs(ny) > 0.5f) { ux = 0.5f; uy = 0; uz = 0; vx = 0; vy = 0; vz = 0.5f; }
            else if (Math.abs(nx) > 0.5f) { ux = 0; uy = 0.5f; uz = 0; vx = 0; vy = 0; vz = 0.5f; }
            else { ux = 0.5f; uy = 0; uz = 0; vx = 0; vy = 0.5f; vz = 0; }
            put(bb, cx - ux - vx, cy - uy - vy, cz - uz - vz, rb, gb, bbyte);
            put(bb, cx + ux - vx, cy + uy - vy, cz + uz - vz, rb, gb, bbyte);
            put(bb, cx + ux + vx, cy + uy + vy, cz + uz + vz, rb, gb, bbyte);
            put(bb, cx - ux + vx, cy - uy + vy, cz - uz + vz, rb, gb, bbyte);
            q++;
        }
        bb.flip();
        Result res = new Result(); res.originX = p.ox; res.originZ = p.oz; res.quadCount = q; res.quads = bb;
        return res;
    }

    private static void put(ByteBuffer bb, float x, float y, float z, byte r, byte g, byte b) {
        bb.putFloat(x).putFloat(y).putFloat(z).put(r).put(g).put(b).put((byte) -1);
    }

    // ------------------------------------------------------------------ immediate block updates
    private final class Listener implements IWorldEventListener {
        public void notifyBlockUpdate(World w, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            vol.set(pos.getX(), pos.getY(), pos.getZ(), VoxelVolume.classify(newState));
        }
        public void notifyLightSet(BlockPos pos) {}
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
        public void playSoundToAllNearExcept(EntityPlayer pl, SoundEvent s, SoundCategory c, double x, double y, double z, float v, float pi) {}
        public void playRecord(SoundEvent s, BlockPos pos) {}
        public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double xs, double ys, double zs, int... params) {}
        public void spawnParticle(int id, boolean ignoreRange, boolean minimal, double x, double y, double z, double xs, double ys, double zs, int... params) {}
        public void onEntityAdded(Entity e) {}
        public void onEntityRemoved(Entity e) {}
        public void broadcastSound(int soundID, BlockPos pos, int data) {}
        public void playEvent(EntityPlayer pl, int type, BlockPos pos, int data) {}
        public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {}
    }
}
