package com.ultraview.lod.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/** Amplia o plano distante (far) da projecao atual mantendo FOV, aspecto e plano proximo (profundidade compativel). */
public final class ProjectionUtil {
    private static final FloatBuffer BUF = BufferUtils.createFloatBuffer(16);

    private ProjectionUtil() { }

    /** Deve ser chamado com a matriz de projecao selecionada (GL_PROJECTION). Retorna true se alterou. */
    public static boolean ensureFar(float needFar) {
        BUF.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, BUF);
        float m10 = BUF.get(10), m11 = BUF.get(11), m14 = BUF.get(14);
        if (Math.abs(m11 + 1.0f) > 0.01f) return false; // nao e perspectiva
        float near = m14 / (m10 - 1.0f);
        float far = m14 / (m10 + 1.0f);
        if (!(near > 0) || far >= needFar) return false;
        BUF.put(10, -(needFar + near) / (needFar - near));
        BUF.put(14, -2.0f * needFar * near / (needFar - near));
        BUF.rewind();
        GL11.glLoadMatrix(BUF);
        return true;
    }
}
