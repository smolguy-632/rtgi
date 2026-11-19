package com.rtgi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

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
        // Every state below is set through GlStateManager AND forced with a raw GL11 call.
        // GlStateManager caches state; if another mod (e.g. Celeritas) changed GL directly, the
        // cache can be stale and our enable would silently no-op. The raw call always applies the
        // same value we just told GlStateManager, so GL ends up matching its cache either way.
        GlStateManager.disableTexture2D();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GlStateManager.disableLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GlStateManager.disableCull();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GlStateManager.enableBlend();
        GL11.glEnable(GL11.GL_BLEND);
        GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        GlStateManager.enableDepth();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GlStateManager.depthFunc(515);          // GL_LEQUAL
        GL11.glDepthFunc(515);
        GlStateManager.depthMask(false);
        GL11.glDepthMask(false);
        GlStateManager.enablePolygonOffset();
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GlStateManager.doPolygonOffset(-1.0F, -1.0F);
        GL11.glPolygonOffset(-1.0F, -1.0F);

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
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GlStateManager.depthMask(true);
        GL11.glDepthMask(true);
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GL11.glDisable(GL11.GL_BLEND);
        GlStateManager.enableCull();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GlStateManager.enableLighting();
        GL11.glEnable(GL11.GL_LIGHTING);
        GlStateManager.enableTexture2D();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GlStateManager.popMatrix();
    }
}
