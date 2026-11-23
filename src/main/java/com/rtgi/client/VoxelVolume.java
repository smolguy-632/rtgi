package com.rtgi.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * 256^3 toroidal voxel mirror of the client world (full height, 16 chunks wide).
 * cells: palette id (0 = air), bit 15 set = exposed water surface (for the RT kernel).
 * light: packed (sky<<4)|block per voxel, indexed by world coords wrapped into 256.
 */
public final class VoxelVolume {
    public static final int SIZE = 256;

    public short[] cells = new short[SIZE * SIZE * SIZE];
    public byte[] light = new byte[SIZE * SIZE * SIZE];
    public final short[] sectionCount = new short[16 * 16 * 16];
    private final int[] slotCx = new int[256], slotCz = new int[256];
    public volatile int originX, originZ;
    private int cursor;
    public BlockPalette palette;

    public VoxelVolume() { reset(); }

    public synchronized void reset() {
        Arrays.fill(cells, (short) 0);
        Arrays.fill(light, (byte) 0);
        Arrays.fill(sectionCount, (short) 0);
        Arrays.fill(slotCx, Integer.MIN_VALUE);
        Arrays.fill(slotCz, Integer.MIN_VALUE);
        cursor = 0; originX = 0; originZ = 0;
    }

    public static int cellIndex(int wx, int wy, int wz) { return ((wx & 255) << 16) | (wy << 8) | (wz & 255); }
    public static int secIndex(int wx, int wy, int wz) { return (((wx >> 4) & 15) << 8) | ((wy >> 4) << 4) | ((wz >> 4) & 15); }

    public short get(int wx, int wy, int wz) { return cells[cellIndex(wx, wy, wz)]; }

    public short classify(IBlockState st) {
        if (palette == null) return st.isOpaqueCube() ? (short) 1 : (short) 0;
        return palette.idOf(st);
    }

    /** Slide the window to the player's chunk and invalidate columns that just entered it. */
    public void recenter(int playerChunkX, int playerChunkZ) {
        int ocx = playerChunkX - 8, ocz = playerChunkZ - 8;
        originX = ocx << 4; originZ = ocz << 4;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int cx = ocx + dx, cz = ocz + dz;
                int slot = (cx & 15) | ((cz & 15) << 4);
                if (slotCx[slot] != cx || slotCz[slot] != cz) {
                    clearColumn(cx, cz);
                    slotCx[slot] = cx; slotCz[slot] = cz;
                }
            }
        }
    }

    private void clearColumn(int cx, int cz) {
        int bz = cz << 4;
        for (int x = 0; x < 16; x++) {
            int wx = (cx << 4) + x;
            for (int y = 0; y < 256; y++) {
                int base = cellIndex(wx, y, bz);
                Arrays.fill(cells, base, base + 16, (short) 0);
                Arrays.fill(light, base, base + 16, (byte) 0);
            }
        }
        for (int sy = 0; sy < 16; sy++) sectionCount[secIndex(cx << 4, sy << 4, bz)] = 0;
    }

    /** Re-read up to {@code budget} sections of loaded chunks (round robin). */
    public void scan(World world, int budget, int playerCx, int playerCz, int radius) {
        int steps = 0, done = 0;
        while (done < budget && steps < 4096) {
            int c = cursor; cursor = (cursor + 1) & 4095; steps++;
            int sy = c & 15, col = c >> 4;
            int cx = (originX >> 4) + (col & 15), cz = (originZ >> 4) + (col >> 4);
            if (Math.abs(cx - playerCx) > radius || Math.abs(cz - playerCz) > radius) continue;
            Chunk ch = world.getChunkProvider().getLoadedChunk(cx, cz);
            if (ch == null) continue;
            scanSection(ch, cx, cz, sy, world);
            done++;
        }
    }

    private void scanSection(Chunk ch, int cx, int cz, int sy, World world) {
        ExtendedBlockStorage s = ch.getBlockStorageArray()[sy];
        int bx = cx << 4, by = sy << 4, bz = cz << 4, cnt = 0;
        if (s == null || s.isEmpty()) {
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++) {
                    int base = cellIndex(bx + x, by + y, bz);
                    Arrays.fill(cells, base, base + 16, (short) 0);
                    Arrays.fill(light, base, base + 16, (byte) 0);
                }
        } else {
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        IBlockState st = s.get(x, y, z);
                        short id = classify(st);
                        // water surface flag: water cell whose up-neighbour is air
                        if (id != 0 && palette != null && (palette.flags[id] & 8) != 0) {
                            int ux = bx + x, uy = by + y + 1, uz = bz + z;
                            short up = (uy > 255) ? 0 : get(ux, uy, uz);
                            if (up == 0) id = (short) (id | 0x8000);
                        }
                        cells[cellIndex(bx + x, by + y, bz + z)] = id;
                        if (id != 0) cnt++;
                        // light
                        int lx = bx + x, ly = by + y, lz = bz + z;
                        int skyL = 0, blkL = 0;
                        try {
                            skyL = ch.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, new net.minecraft.util.math.BlockPos(lx, ly, lz));
                            blkL = ch.getLightFor(net.minecraft.world.EnumSkyBlock.BLOCK, new net.minecraft.util.math.BlockPos(lx, ly, lz));
                        } catch (Throwable ignore) {}
                        light[cellIndex(lx, ly, lz)] = (byte) ((Math.min(15, skyL) << 4) | Math.min(15, blkL));
                    }
        }
        sectionCount[secIndex(bx, by, bz)] = (short) cnt;
    }

    /** Immediate single block update (from the world event listener). */
    public void set(int wx, int wy, int wz, short v) {
        if (wy < 0 || wy > 255) return;
        int cx = wx >> 4, cz = wz >> 4;
        if (cx < (originX >> 4) || cx >= (originX >> 4) + 16 || cz < (originZ >> 4) || cz >= (originZ >> 4) + 16) return;
        int idx = cellIndex(wx, wy, wz);
        short old = cells[idx];
        if (old == v) return;
        int si = secIndex(wx, wy, wz);
        if (old == 0) sectionCount[si]++; else if (v == 0) sectionCount[si]--;
        cells[idx] = v;
    }

    public void setLight(int wx, int wy, int wz, byte v) {
        if (wy < 0 || wy > 255) return;
        light[cellIndex(wx, wy, wz)] = v;
    }
}