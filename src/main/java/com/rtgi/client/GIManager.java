package com.rtgi.client;

import com.rtgi.RTGIConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GIManager {
    public static final class RTResult { public int w, h; public int[] color; }

    private static final Logger LOG = LogManager.getLogger("RTGI");

    private final VoxelVolume vol = new VoxelVolume();
    private final BlockPalette palette = new BlockPalette();
    private final RTOverlay overlay = new RTOverlay();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<RTResult> pending = new AtomicReference<RTResult>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) { Thread t = new Thread(r, "RTGI-Worker"); t.setDaemon(true); return t; }
    });
    private final IWorldEventListener listener = new Listener();

    private GIEngine engine;
    private volatile boolean disabled;
    private int failures, tickCounter;
    private World lastWorld;

    private volatile float[] lastProj, lastModel;
    private volatile float lastPartialTicks;
    private volatile int lastOriginX, lastOriginZ;

    public GIManager() { vol.palette = palette; }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load e) {
        if (e.getWorld().isRemote) { e.getWorld().addEventListener(listener); vol.palette = palette; }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload e) {
        if (e.getWorld().isRemote) { e.getWorld().removeEventListener(listener); overlay.clear(); vol.reset(); }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || disabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!RTGIConfig.enabled || mc.world == null || mc.player == null) return;
        if (mc.world != lastWorld) {
            lastWorld = mc.world; vol.reset(); vol.palette = palette; overlay.clear(); pending.set(null); tickCounter = 0;
        }
        int pcx = MathHelper.floor(mc.player.posX) >> 4, pcz = MathHelper.floor(mc.player.posZ) >> 4;
        vol.recenter(pcx, pcz);
        vol.scan(mc.world, RTGIConfig.scanSectionsPerTick, pcx, pcz, RTGIConfig.radiusChunks);

        if (++tickCounter >= RTGIConfig.refreshIntervalTicks && busy.compareAndSet(false, true)) {
            tickCounter = 0;
            final int ox = vol.originX, oz = vol.originZ;
            lastOriginX = ox; lastOriginZ = oz;
            Entity eye = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.player;
            final float camx = (float) (eye.posX - ox);
            final float camy = (float) (eye.posY + eye.getEyeHeight());
            final float camz = (float) (eye.posZ - oz);
            final float maxDist = (float) RTGIConfig.maxRayDistance;
            final float[] proj = lastProj, model = lastModel;
            final float pt = lastPartialTicks;
            pool.execute(new Runnable() { public void run() {
                runJob(ox, oz, camx, camy, camz, maxDist, proj, model, pt, mc.world);
            } });
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!RTGIConfig.enabled || disabled) return;
        // capture matrices for next job
        float[] p = new float[16], m = new float[16];
        java.nio.FloatBuffer pb = org.lwjgl.BufferUtils.createFloatBuffer(16);
        java.nio.FloatBuffer mb = org.lwjgl.BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, pb);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mb);
        pb.get(p); mb.get(m);
        lastProj = p; lastModel = m; lastPartialTicks = e.getPartialTicks();
        RTResult r = pending.getAndSet(null);
        if (r != null) overlay.upload(r.color, r.w, r.h);
        overlay.draw();
    }

    private void runJob(int ox, int oz, float camx, float camy, float camz, float maxDist,
                        float[] proj, float[] model, float pt, World world) {
        try {
            if (engine == null) {
                engine = GIEngine.create(RTGIConfig.openclDevice, RTGIConfig.skipD3D12Devices);
                LOG.info("RT GI using OpenCL device: {}", engine.deviceName);
            }
            if (proj == null || model == null) { busy.set(false); return; }

            long t0 = System.nanoTime();
            Tree64 tree = Tree64.build(vol, ox, oz);
            long t1 = System.nanoTime();

            // colTint: 3 rows x 256 columns
            int[] colTint = new int[3 * 256];
            for (int sx = 0; sx < 16; sx++) for (int sz = 0; sz < 16; sz++) {
                int slot = sx | (sz << 4);
                int wx = ox + sx * 16 + 8, wz = oz + sz * 16 + 8;
                int wy = 64;
                try { wy = (int) Minecraft.getMinecraft().player.posY; } catch (Throwable ignore) {}
                int grass = 0x59AE30, foliage = 0x59AE30, water = 0x3F76E4;
                colTint[0 * 256 + slot] = grass;
                colTint[1 * 256 + slot] = foliage;
                colTint[2 * 256 + slot] = water;
            }
            long t2 = System.nanoTime();

            // emitters
            int[] emHead = new int[4096];
            Arrays.fill(emHead, -1);
            java.util.ArrayList<Integer> nextList = new java.util.ArrayList<Integer>();
            java.util.ArrayList<Float> posList = new java.util.ArrayList<Float>();
            for (int x = 0; x < 256; x++) for (int y = 0; y < 256; y++) for (int z = 0; z < 256; z++) {
                short cell = (short) (vol.cells[VoxelVolume.cellIndex(ox + x, y, oz + z)] & 0x7fff);
                if (cell == 0) continue;
                if ((palette.flags[cell] & BlockPalette.F_EMIT) == 0) continue;
                int level = palette.emitLevel[cell];
                if (level <= 0) continue;
                int sx = x >> 4, sy = y >> 4, sz = z >> 4;
                int slot = (sx << 8) | (sy << 4) | sz;
                int idx = posList.size() / 4;
                nextList.add(emHead[slot]);
                emHead[slot] = idx;
                posList.add((float) x); posList.add((float) y); posList.add((float) z); posList.add((float) level);
            }
            int emitterCount = posList.size() / 4;
            int[] emNext = new int[Math.max(1, emitterCount)];
            float[] emPos = new float[Math.max(4, emitterCount * 4)];
            for (int i = 0; i < emitterCount; i++) {
                emNext[i] = nextList.get(i);
                emPos[i * 4] = posList.get(i * 4);
                emPos[i * 4 + 1] = posList.get(i * 4 + 1);
                emPos[i * 4 + 2] = posList.get(i * 4 + 2);
                emPos[i * 4 + 3] = posList.get(i * 4 + 3);
            }
            long t3 = System.nanoTime();

            // atlas
            int[] atlas = captureAtlas();
            int atlasW = 256, atlasH = 256;
            try {
                net.minecraft.client.renderer.texture.AbstractTexture tex =
                        (net.minecraft.client.renderer.texture.AbstractTexture) Minecraft.getMinecraft().getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
                try { atlasW = (Integer) tex.getClass().getMethod("getWidth").invoke(tex); } catch (Throwable ignore) {}
                try { atlasH = (Integer) tex.getClass().getMethod("getHeight").invoke(tex); } catch (Throwable ignore) {}
                // fallback to fields
                if (atlasW <= 0) { try { atlasW = tex.getClass().getField("width").getInt(tex); } catch (Throwable ignore) {} }
                if (atlasH <= 0) { try { atlasH = tex.getClass().getField("height").getInt(tex); } catch (Throwable ignore) {} }
            } catch (Throwable ignore) {}
            if (atlas == null || atlas.length == 0) { atlas = new int[]{0}; atlasW = 1; atlasH = 1; }

            // render scale
            int outW = Math.max(64, (int) (Minecraft.getMinecraft().displayWidth * RTGIConfig.renderScale));
            int outH = Math.max(64, (int) (Minecraft.getMinecraft().displayHeight * RTGIConfig.renderScale));

            // matrices: VP = proj * model (column-major), invVP
            float[] VP = mulMat4(proj, model);
            float[] invVP = invertMat4(VP);

            // P block (57 floats)
            float[] P = new float[57];
            System.arraycopy(invVP, 0, P, 0, 16);
            System.arraycopy(VP, 0, P, 16, 16);
            P[32] = camx; P[33] = camy; P[34] = camz;
            // sun dir
            float angle = (world != null) ? world.getCelestialAngle(pt) : 0.5f;
            float theta = angle * (float) (Math.PI * 2);
            P[35] = (float) (-Math.sin(theta)); P[36] = (float) Math.cos(theta); P[37] = 0f;
            // sun color
            float sunB = (world != null) ? world.getSunBrightness(pt) : 1f;
            P[38] = sunB; P[39] = sunB * 0.96f; P[40] = sunB * 0.9f;
            // zenith
            net.minecraft.util.math.Vec3d sky = (world != null) ? world.getSkyColor(Minecraft.getMinecraft().player, pt) : new net.minecraft.util.math.Vec3d(0.5,0.7,1.0);
            P[41] = (float) sky.x; P[42] = (float) sky.y; P[43] = (float) sky.z;
            // fog
            net.minecraft.util.math.Vec3d fog = (world != null) ? world.getFogColor(pt) : new net.minecraft.util.math.Vec3d(0.7,0.8,1.0);
            P[44] = (float) fog.x; P[45] = (float) fog.y; P[46] = (float) fog.z;
            int rd = Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
            float fogEnd = rd * 16f;
            P[47] = fogEnd * 0.5f; // fogStart
            P[48] = fogEnd;         // fogEnd
            P[49] = (world != null) ? (float) (world.getTotalWorldTime() + pt) : 0f; // time
            P[50] = maxDist;        // maxDist
            P[51] = Math.min(maxDist, 32f); // shadowDist
            P[52] = 0.1f;           // wave
            P[53] = sunB * 0.4f;    // skyAmb
            P[54] = 0.3f;           // blkDark
            P[55] = RTGIConfig.sunShadows ? 1f : 0f;
            P[56] = RTGIConfig.waterReflection ? 1f : 0f;

            int[] IP = new int[7];
            IP[0] = outW; IP[1] = outH; IP[2] = ox; IP[3] = oz;
            IP[4] = atlasW; IP[5] = atlasH; IP[6] = RTGIConfig.blockShadows ? 1 : 0;

            long t4 = System.nanoTime();
            GIEngine.Result res = engine.run(tree, vol, palette, colTint, emHead, emNext, emPos, emitterCount,
                    atlas, atlasW, atlasH, P, IP, outW, outH);
            long t5 = System.nanoTime();

            RTResult r = new RTResult();
            r.w = res.w; r.h = res.h; r.color = res.color;
            pending.set(r);
            failures = 0;
            if (RTGIConfig.debugLog) {
                LOG.info("RT pass: tree {}ms ({} nodes), colTint {}ms, emitters {}ms ({}), gpu+read {}ms, upload-pending",
                        (t1 - t0) / 1e6, tree.nodeCount, (t2 - t1) / 1e6, (t3 - t2) / 1e6, emitterCount, (t5 - t4) / 1e6);
            }
        } catch (Throwable t) {
            LOG.error("RT GI pass failed", t);
            if (engine == null || ++failures >= 3) { disabled = true; LOG.error("RT GI disabled for this session."); }
        } finally {
            busy.set(false);
        }
    }

    private static float[] mulMat4(float[] a, float[] b) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[i + k * 4] * b[k + j * 4];
                r[i + j * 4] = s;
            }
        return r;
    }

    private static float[] invertMat4(float[] m) {
        float[] a = new float[16];
        System.arraycopy(m, 0, a, 0, 16);
        // augment
        float[] inv = new float[16];
        // gaussian elimination on [m | I]
        float[][] M = new float[4][8];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { M[i][j] = a[i + j * 4]; M[i][j + 4] = (i == j) ? 1f : 0f; }
        for (int col = 0; col < 4; col++) {
            // pivot
            int piv = col;
            float best = Math.abs(M[col][col]);
            for (int r = col + 1; r < 4; r++) if (Math.abs(M[r][col]) > best) { best = Math.abs(M[r][col]); piv = r; }
            if (best < 1e-12f) return new float[16]; // singular
            if (piv != col) { float[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp; }
            float pv = M[col][col];
            for (int j = 0; j < 8; j++) M[col][j] /= pv;
            for (int r = 0; r < 4; r++) {
                if (r == col) continue;
                float factor = M[r][col];
                for (int j = 0; j < 8; j++) M[r][j] -= factor * M[col][j];
            }
        }
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) inv[i + j * 4] = M[i][j + 4];
        return inv;
    }

    private static int[] captureAtlas() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            net.minecraft.client.renderer.texture.AbstractTexture tex =
                    (net.minecraft.client.renderer.texture.AbstractTexture) mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
            int w = 256, h = 256;
            try { w = (Integer) tex.getClass().getMethod("getWidth").invoke(tex); } catch (Throwable ignore) {}
            try { h = (Integer) tex.getClass().getMethod("getHeight").invoke(tex); } catch (Throwable ignore) {}
            if (w <= 0) { try { w = tex.getClass().getField("width").getInt(tex); } catch (Throwable ignore) {} }
            if (h <= 0) { try { h = tex.getClass().getField("height").getInt(tex); } catch (Throwable ignore) {} }
            if (w <= 0 || h <= 0) return null;
            int id = tex.getGlTextureId();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            int[] out = new int[w * h];
            for (int i = 0; i < out.length; i++) {
                int r = buf.get() & 0xFF;
                int g = buf.get() & 0xFF;
                int b = buf.get() & 0xFF;
                int a = buf.get() & 0xFF;
                out[i] = r | (g << 8) | (b << 16) | (a << 24);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private final class Listener implements IWorldEventListener {
        public void notifyBlockUpdate(World w, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            vol.set(pos.getX(), pos.getY(), pos.getZ(), (short) (vol.palette != null ? vol.palette.idOf(newState) : 0));
            // light update deferred (kernel reads vol.light; update via notifyLightSet)
        }
        public void notifyLightSet(BlockPos pos) {
            // update light byte for this cell from the world
            try {
                World ww = Minecraft.getMinecraft().world;
                if (ww == null) return;
                net.minecraft.world.chunk.Chunk ch = ww.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
                if (ch == null) return;
                int sky = ch.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, pos);
                int blk = ch.getLightFor(net.minecraft.world.EnumSkyBlock.BLOCK, pos);
                vol.setLight(pos.getX(), pos.getY(), pos.getZ(), (byte) ((Math.min(15, sky) << 4) | Math.min(15, blk)));
            } catch (Throwable ignore) {}
        }
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
        public void playSoundToAllNearExcept(net.minecraft.entity.player.EntityPlayer pl, net.minecraft.util.SoundEvent s, net.minecraft.util.SoundCategory c, double x, double y, double z, float v, float pi) {}
        public void playRecord(net.minecraft.util.SoundEvent s, BlockPos pos) {}
        public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double xs, double ys, double zs, int... params) {}
        public void spawnParticle(int id, boolean ignoreRange, boolean minimal, double x, double y, double z, double xs, double ys, double zs, int... params) {}
        public void onEntityAdded(net.minecraft.entity.Entity e) {}
        public void onEntityRemoved(net.minecraft.entity.Entity e) {}
        public void broadcastSound(int soundID, BlockPos pos, int data) {}
        public void playEvent(net.minecraft.entity.player.EntityPlayer pl, int type, BlockPos pos, int data) {}
        public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {}
    }
}