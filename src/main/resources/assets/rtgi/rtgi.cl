// RT GI kernel. Material byte: 0 air, 1 opaque solid, 16+L emitter with light level L (1..15).
#define LEVELS 4
#define TREE_SIZE 256.0f

// ---- sparse 64-tree, hierarchical DDA ---------------------------------------
// node = 4x4x4 children, ulong mask, children addressed with popcount(mask & below).
// Ray state per level lives in a small private stack; cell advance is classic A&W
// at the current level's cell size, descending when a set bit is entered and
// ascending when the ray leaves the node's 4x4x4 cell range.
inline uchar trace_tree(__global const ulong* masks, __global const uint* ptrs, __global const uchar* mats,
                        float3 o, float3 d, float max_t, float* t_out)
{
    if (fabs(d.x) < 1e-6f) d.x = copysign(1e-6f, d.x);
    if (fabs(d.y) < 1e-6f) d.y = copysign(1e-6f, d.y);
    if (fabs(d.z) < 1e-6f) d.z = copysign(1e-6f, d.z);
    float3 inv = 1.0f / d;
    float3 ainv = fabs(inv);
    int sx = d.x > 0.0f ? 1 : -1, sy = d.y > 0.0f ? 1 : -1, sz = d.z > 0.0f ? 1 : -1;

    float3 t0 = (0.0f - o) * inv, t1 = (TREE_SIZE - o) * inv;
    float3 tn = fmin(t0, t1), tf = fmax(t0, t1);
    float t = fmax(fmax(tn.x, tn.y), fmax(tn.z, 0.0f));
    float t_end = fmin(fmin(tf.x, tf.y), fmin(tf.z, max_t));
    if (t >= t_end) { *t_out = 1e30f; return 0; }

    int s_node[LEVELS]; int s_cx[LEVELS], s_cy[LEVELS], s_cz[LEVELS];
    int s_ox[LEVELS], s_oy[LEVELS], s_oz[LEVELS];
    float s_tx[LEVELS], s_ty[LEVELS], s_tz[LEVELS];

    int level = LEVELS - 1, cs = 64;
    int node = 0, ox = 0, oy = 0, oz = 0;
    ulong m = masks[0];

    float3 p = o + d * t;
    int cx = clamp((int)floor((p.x - ox) / cs), 0, 3);
    int cy = clamp((int)floor((p.y - oy) / cs), 0, 3);
    int cz = clamp((int)floor((p.z - oz) / cs), 0, 3);
    float tx = (ox + (cx + (sx > 0)) * cs - o.x) * inv.x;
    float ty = (oy + (cy + (sy > 0)) * cs - o.y) * inv.y;
    float tz = (oz + (cz + (sz > 0)) * cs - o.z) * inv.z;

    for (int it = 0; it < 1024; it++) {
        int bit = cx + cy * 4 + cz * 16;
        if ((m >> bit) & 1UL) {
            uint c = ptrs[node] + (uint)popcount(m & ((1UL << bit) - 1UL));
            if (level == 0) { *t_out = t; return mats[c]; }
            // descend
            s_node[level] = node; s_cx[level] = cx; s_cy[level] = cy; s_cz[level] = cz;
            s_ox[level] = ox; s_oy[level] = oy; s_oz[level] = oz;
            s_tx[level] = tx; s_ty[level] = ty; s_tz[level] = tz;
            ox += cx * cs; oy += cy * cs; oz += cz * cs;
            level--; cs >>= 2; node = (int)c; m = masks[node];
            p = o + d * t;
            cx = clamp((int)floor((p.x - ox) / cs), 0, 3);
            cy = clamp((int)floor((p.y - oy) / cs), 0, 3);
            cz = clamp((int)floor((p.z - oz) / cs), 0, 3);
            tx = (ox + (cx + (sx > 0)) * cs - o.x) * inv.x;
            ty = (oy + (cy + (sy > 0)) * cs - o.y) * inv.y;
            tz = (oz + (cz + (sz > 0)) * cs - o.z) * inv.z;
            continue;
        }
        // advance to next cell on this level
        int axis;
        if (tx < ty) { axis = (tx < tz) ? 0 : 2; } else { axis = (ty < tz) ? 1 : 2; }
        t = (axis == 0) ? tx : ((axis == 1) ? ty : tz);
        if (t >= t_end) break;
        bool out;
        if (axis == 0)      { cx += sx; tx += cs * ainv.x; out = (cx < 0 || cx > 3); }
        else if (axis == 1) { cy += sy; ty += cs * ainv.y; out = (cy < 0 || cy > 3); }
        else                { cz += sz; tz += cs * ainv.z; out = (cz < 0 || cz > 3); }
        while (out) {                     // left this node: pop to parent and step its cell
            if (level == LEVELS - 1) { *t_out = 1e30f; return 0; }
            level++; cs <<= 2;
            node = s_node[level]; m = masks[node];
            cx = s_cx[level]; cy = s_cy[level]; cz = s_cz[level];
            ox = s_ox[level]; oy = s_oy[level]; oz = s_oz[level];
            tx = s_tx[level]; ty = s_ty[level]; tz = s_tz[level];
            if (axis == 0)      { cx += sx; tx += cs * ainv.x; out = (cx < 0 || cx > 3); }
            else if (axis == 1) { cy += sy; ty += cs * ainv.y; out = (cy < 0 || cy > 3); }
            else                { cz += sz; tz += cs * ainv.z; out = (cz < 0 || cz > 3); }
        }
    }
    *t_out = 1e30f;
    return 0;
}

__kernel void gi_tree64(__global const ulong* masks, __global const uint* ptrs, __global const uchar* mats,
                        __global const float4* pos, __global const float4* nrm,
                        __global float4* out, const int n,
                        const float camx, const float camy, const float camz,
                        const float max_dist,
                        const float skyr, const float skyg, const float skyb,
                        const float ambient)
{
    int i = get_global_id(0);
    if (i >= n) return;
    float3 p = pos[i].xyz, nn = nrm[i].xyz;
    float3 inc = normalize(p - (float3)(camx, camy, camz));
    float3 d = inc - 2.0f * dot(inc, nn) * nn;            // perfectly smooth mirror, exactly 1 ray per voxel
    float t;
    uchar m = trace_tree(masks, ptrs, mats, p, d, max_dist, &t);
    float3 col; float w;
    if (m == 0) { col = (float3)(skyr, skyg, skyb); w = -1.0f; }
    else {
        float att = 1.0f / fmax(1.0f, t * t);               // inverse square law
        if (m >= 16) col = (float3)(1.0f, 0.78f, 0.45f) * (((float)(m - 16)) / 15.0f * 16.0f) * att;
        else         col = (float3)(ambient) * att;
        w = t;
    }
    out[i] = (float4)(col, w);
}
