package com.rtgi.client;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumBlockLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;

import java.util.Random;

/**
 * Maps each IBlockState to a compact palette id (0 = air) and fills the per-id arrays
 * consumed by the RT kernel: palFlags, palUV (6 faces), palTint (6 faces), palBox (min/max).
 */
public final class BlockPalette {
    public static final int F_CUTOUT = 2;
    public static final int F_TRANSL = 4;
    public static final int F_WATER = 8;
    public static final int F_FULL = 16;
    public static final int F_EMIT = 32;
    public static final int F_SHADOW = 64;

    private static final int MAX = 1 << 15;
    public final int[] flags = new int[MAX];
    public final float[] uv = new float[MAX * 24];   // id*24 + face*4
    public final int[] tint = new int[MAX * 6];      // id*6 + face
    public final float[] box = new float[MAX * 8];   // id*8 : min(3)+max(3) (float4-ish, only xyz used)
    public final int[] emitLevel = new int[MAX];

    private final java.util.Map<IBlockState, Short> ids = new java.util.IdentityHashMap<IBlockState, Short>();
    private short next = 1;
    public short maxId = 0;

    public short idOf(IBlockState st) {
        if (st == null) return 0;
        Short v = ids.get(st);
        if (v != null) return v;
        short id = next++;
        if (id >= MAX) { next--; return 0; }
        ids.put(st, id);
        build(id, st);
        if (id > maxId) maxId = id;
        return id;
    }

    private void build(short id, IBlockState st) {
        Block b = st.getBlock();
        String name = String.valueOf(b.getRegistryName()).toLowerCase();

        // flags
        int f = 0;
        boolean full = false;
        try { full = st.isFullCube(); } catch (Throwable ignore) {}
        if (full) f |= F_FULL;
        EnumBlockLayer layer = EnumBlockLayer.SOLID;
        try { layer = b.getRenderLayer(); } catch (Throwable ignore) {}
        if (layer == EnumBlockLayer.CUTOUT || layer == EnumBlockLayer.CUTOUT_MIPPED) f |= F_CUTOUT;
        if (layer == EnumBlockLayer.TRANSLUCENT) f |= F_TRANSL;
        boolean isWater = name.contains("water") || name.contains("flowing_water") || name.contains("fluid");
        if (isWater) f |= F_WATER;
        int lv = 0;
        try { lv = st.getLightValue(); } catch (Throwable ignore) {}
        if (lv > 0) f |= F_EMIT;
        boolean opaque = false;
        try { opaque = st.isOpaqueCube(); } catch (Throwable ignore) {}
        if (opaque) f |= F_SHADOW;
        flags[id] = f;
        emitLevel[id] = lv;

        // tint type per block
        int tp = 0;
        if (lv == 0 || true) {
            if (isWater) tp = 3;
            else if (name.contains("leaves") || name.contains("sapling")) tp = 2;
            else if (name.contains("grass") || name.contains("fern") || name.contains("vine")
                    || name.contains("lily") || name.contains("double_plant") || name.contains("flower")
                    || name.contains("tallgrass")) tp = 1;
        }

        // model quads
        IBakedModel model = null;
        try {
            model = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelForState(st);
        } catch (Throwable ignore) {}
        Random rand = new Random(0);
        EnumFacing[] faces = { EnumFacing.UP, EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST };
        for (int fi = 0; fi < 6; fi++) {
            EnumFacing facing = faces[fi];
            java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads = null;
            if (model != null) {
                try { quads = model.getQuads(st, facing, rand); } catch (Throwable ignore) {}
                if (quads == null || quads.isEmpty()) {
                    try { quads = model.getQuads(st, null, rand); } catch (Throwable ignore) {}
                }
            }
            TextureAtlasSprite spr = null;
            int tintIdx = -1;
            if (quads != null && !quads.isEmpty()) {
                net.minecraft.client.renderer.block.model.BakedQuad q = quads.get(0);
                spr = q.getSprite();
                tintIdx = q.getTintIndex();
            }
            if (spr != null) {
                uv[id * 24 + fi * 4 + 0] = spr.getMinU();
                uv[id * 24 + fi * 4 + 1] = spr.getMinV();
                uv[id * 24 + fi * 4 + 2] = spr.getMaxU();
                uv[id * 24 + fi * 4 + 3] = spr.getMaxV();
            } else {
                uv[id * 24 + fi * 4 + 0] = 0f;
                uv[id * 24 + fi * 4 + 1] = 0f;
                uv[id * 24 + fi * 4 + 2] = 1f;
                uv[id * 24 + fi * 4 + 3] = 1f;
            }
            // tint encoding: high byte tp (1/2/3 = colTint row, 4 = fixed), low 24 bits color
            int t = 0;
            int useTp = tp;
            if (tintIdx == -1) useTp = 0;
            if (useTp >= 1 && useTp <= 3) {
                t = useTp << 24;
            } else if (useTp == 4) {
                t = (4 << 24) | 0xFFFFFF;
            }
            tint[id * 6 + fi] = t;
        }

        // bounding box
        float minx = 0, miny = 0, minz = 0, maxx = 1, maxy = 1, maxz = 1;
        if (!full) {
            net.minecraft.util.math.AxisAlignedBB bb = null;
            try { bb = b.getCollisionBoundingBox(st, null, null); } catch (Throwable ignore) {}
            if (bb != null) {
                minx = (float) bb.minX; miny = (float) bb.minY; minz = (float) bb.minZ;
                maxx = (float) bb.maxX; maxy = (float) bb.maxY; maxz = (float) bb.maxZ;
            }
        }
        box[id * 8 + 0] = minx; box[id * 8 + 1] = miny; box[id * 8 + 2] = minz; box[id * 8 + 3] = 0f;
        box[id * 8 + 4] = maxx; box[id * 8 + 5] = maxy; box[id * 8 + 6] = maxz; box[id * 8 + 7] = 0f;
    }

    public int maxId() { return maxId; }
}