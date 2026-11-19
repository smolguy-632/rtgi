package com.rtgi;

import net.minecraftforge.common.config.Config;

@Config(modid = Tags.MODID, name = "rtgi")
public class RTGIConfig {
    @Config.Comment("Master switch.")
    public static boolean enabled = true;

    @Config.Comment("Run one GI pass every N client ticks (20 ticks = 1 second). Higher = cheaper, laggier lighting.")
    @Config.RangeInt(min = 1, max = 200)
    public static int refreshIntervalTicks = 10;

    @Config.Comment("How many 16x16x16 sections of the world are re-read into the voxel volume per tick.")
    @Config.RangeInt(min = 1, max = 128)
    public static int scanSectionsPerTick = 24;

    @Config.Comment("Radius in chunks around the player that is traced (the volume is 16 chunks wide).")
    @Config.RangeInt(min = 2, max = 8)
    public static int radiusChunks = 8;

    @Config.Comment("Upper bound on surface voxels (= rays) per pass. Extra voxels are skipped evenly.")
    @Config.RangeInt(min = 1000, max = 1000000)
    public static int maxSamples = 250000;

    @Config.Comment("Max ray length in blocks. Light falls off with 1/d^2 anyway.")
    @Config.RangeDouble(min = 8, max = 256)
    public static double maxRayDistance = 64;

    @Config.Comment("Brightness multiplier of the added light.")
    @Config.RangeDouble(min = 0, max = 8)
    public static double strength = 1.0;

    @Config.Comment("How much sky colour a ray that escapes adds (0 = none).")
    @Config.RangeDouble(min = 0, max = 1)
    public static double skyContribution = 0.12;

    @Config.Comment("OpenCL GPU index, -1 = first usable GPU.")
    @Config.RangeInt(min = -1, max = 16)
    public static int openclDevice = -1;

    @Config.Comment("Ignore Microsoft D3D12 OpenCL emulation devices (they are slow/unreliable).")
    public static boolean skipD3D12Devices = true;

    @Config.Comment("Log timings of every ~100th pass.")
    public static boolean debugLog = false;
}
