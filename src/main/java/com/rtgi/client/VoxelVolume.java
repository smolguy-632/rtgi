package com.rtgi.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * 256^3 toroidal voxel mirror of the client world (full height, 16 chunks wide).
 * Material byte: 0 air, 1 opaque solid, 16+L light emitter (L = light level 1..15).
 * Storage index uses wrapped x/z so the window can slide without copying.
 */
public final class VoxelVolume {
    public static final int SIZE = 256;

    public final byte[] cells = new byte[SIZE * SIZE * SIZE];
    public final short[] sectionCount = new short[16 * 16 * 16];
    private final int[] slotCx = new int[256], slotCz = new int[256];
    public volatile int originX, originZ;      // world block coords of window min corner (multiples of 16)
    private int cursor;

    public VoxelVolume() { reset(); }

    public synchronized void reset() {
        Arrays.fill(cells, (byte) 0);
        Arrays.fill(sectionCount, (short) 0);
        Arrays.fill(slotCx, Integer.MIN_VALUE);
        Arrays.fill(slotCz, Integer.MIN_VALUE);
        cursor = 0; originX = 0; originZ = 0;
    }

    public static int cellIndex(int wx, int wy, int wz) { return ((wx & 255) << 16) | (wy << 8) | (wz & 255); }
    public static int secIndex(int wx, int wy, int wz) { return (((wx >> 4) & 15) << 8) | ((wy >> 4) << 4) | ((wz >> 4) & 15); }

    public byte get(int wx, int wy, int wz) { return cells[cellIndex(wx, wy, wz)]; }

    public static byte classify(IBlockState st) {
        int l = st.getLightValue();
        if (l > 0) return (byte) (16 + Math.min(15, l));
        return st.isOpaqueCube() ? (byte) 1 : (byte) 0;
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
                Arrays.fill(cells, base, base + 16, (byte) 0);
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
            scanSection(ch, cx, cz, sy);
            done++;
        }
    }

    private void scanSection(Chunk ch, int cx, int cz, int sy) {
        ExtendedBlockStorage s = ch.getBlockStorageArray()[sy];
        int bx = cx << 4, by = sy << 4, bz = cz << 4, cnt = 0;
        if (s == null || s.isEmpty()) {
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++) {
                    int base = cellIndex(bx + x, by + y, bz);
                    Arrays.fill(cells, base, base + 16, (byte) 0);
                }
        } else {
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        byte m = classify(s.get(x, y, z));
                        cells[cellIndex(bx + x, by + y, bz + z)] = m;
                        if (m != 0) cnt++;
                    }
        }
        sectionCount[secIndex(bx, by, bz)] = (short) cnt;
    }

    /** Immediate single block update (from the world event listener). */
    public void set(int wx, int wy, int wz, byte v) {
        if (wy < 0 || wy > 255) return;
        int cx = wx >> 4, cz = wz >> 4;
        if (cx < (originX >> 4) || cx >= (originX >> 4) + 16 || cz < (originZ >> 4) || cz >= (originZ >> 4) + 16) return;
        int idx = cellIndex(wx, wy, wz);
        byte old = cells[idx];
        if (old == v) return;
        int si = secIndex(wx, wy, wz);
        if (old == 0) sectionCount[si]++; else if (v == 0) sectionCount[si]--;
        cells[idx] = v;
    }
}
