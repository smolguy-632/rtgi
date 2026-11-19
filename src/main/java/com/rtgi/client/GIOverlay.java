package com.rtgi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;

/**
 * Draws the GI result as additively blended quads on top of the already rendered world
 * (RenderWorldLastEvent). Nothing here touches the terrain renderer, so Celeritas is unaffected.
 */
public final class GIOverlay {
    private VertexBuffer vbo;
    private int originX, originZ, quadCount;

    public boolean upload(GIManager.Result r) {
        if (!OpenGlHelper.useVbo()) return false;
        if (vbo != null) vbo.deleteGlBuffers();
        vbo = new VertexBuffer(DefaultVertexFormats.POSITION_COLOR);
        r.quads.rewind();
        vbo.bufferData(r.quads);
        originX = r.originX; originZ = r.originZ; quadCount = r.quadCount;
        return true;
    }

    public void clear() {
        if (vbo != null) { vbo.deleteGlBuffers(); vbo = null; }
        quadCount = 0;
    }

    public void draw(float partialTicks) {
        if (vbo == null || quadCount == 0) return;
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) return;
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

        GlStateManager.pushMatrix();
        GlStateManager.translate(originX - cx, -cy, originZ - cz);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(515);          // GL_LEQUAL
        GlStateManager.depthMask(false);
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0F, -1.0F);

        vbo.bindBuffer();
        GlStateManager.glEnableClientState(32884);   // GL_VERTEX_ARRAY
        GlStateManager.glEnableClientState(32886);   // GL_COLOR_ARRAY
        GlStateManager.glVertexPointer(3, 5126, 16, 0);
        GlStateManager.glColorPointer(4, 5121, 16, 12);
        vbo.drawArrays(7);                           // GL_QUADS
        GlStateManager.glDisableClientState(32884);
        GlStateManager.glDisableClientState(32886);
        vbo.unbindBuffer();

        GlStateManager.disablePolygonOffset();
        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }
}
