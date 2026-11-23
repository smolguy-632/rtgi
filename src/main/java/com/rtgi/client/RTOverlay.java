package com.rtgi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Displays the raytraced image as a fullscreen quad (RenderWorldLastEvent).
 */
public final class RTOverlay {
    private int tex = 0;
    private int w, h;

    public void upload(int[] color, int width, int height) {
        if (tex == 0) { tex = GL11.glGenTextures(); }
        if (width != w || height != h) { w = width; h = height; }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        java.nio.IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(color.length);
        buf.put(color); buf.flip();
        // flip Y for OpenGL (kernel row 0 = top of screen)
        // kernel writes py 0..h-1 top-down into outColor[py*w+px]; OpenGL tex (0,0)=bottom-left.
        // Build a flipped buffer.
        int[] flipped = new int[color.length];
        for (int y = 0; y < h; y++)
            System.arraycopy(color, y * w, flipped, (h - 1 - y) * w, w);
        java.nio.IntBuffer fbuf = org.lwjgl.BufferUtils.createIntBuffer(flipped.length);
        fbuf.put(flipped); fbuf.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, fbuf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public void draw() {
        if (tex == 0 || w == 0) return;
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, 1, 0, 1, -1, 1);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.enableTexture2D();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GlStateManager.color(1, 1, 1, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(0, 0);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, 0);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(0, 1);
        GL11.glEnd();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }

    public void clear() {
        if (tex != 0) { GL11.glDeleteTextures(tex); tex = 0; }
        w = 0; h = 0;
    }
}