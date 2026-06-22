/*
 * Copyright (c) 2026, Pudgy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.pudgy.daynight.render;

import java.awt.Canvas;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Renderable;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Configuration;
import com.pudgy.daynight.Mat4;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL45C.GL_LOWER_LEFT;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.glClipControl;

/**
 * Original from-scratch GPU renderer. Milestone 2a: draw flat terrain tiles
 * through our own pipeline (sloped tiles, models, textures come next).
 */
@Slf4j
@PluginDescriptor(
	name = "Day Night Renderer",
	description = "Original from-scratch GPU renderer (work in progress).",
	tags = {"gpu", "render", "day", "night"},
	loadInSafeMode = false
)
public class DayNightRenderer extends Plugin implements DrawCallbacks
{
	private static final int TILE = 128;
	private static final int GEOM_BYTES = 48 * 1024 * 1024; // 48MB scratch (16 bytes/vertex)

	private static final String HSL_TO_RGB =
		"vec3 hslToRgb(vec3 hsl){\n" +
		"  float hue = hsl.x/64.0+0.0078125; float sat = hsl.y/8.0+0.0625; float lum = hsl.z;\n" +
		"  float v = lum/128.0; float r=v,g=v,b=v;\n" +
		"  float q; if(v<0.5) q=v*(1.0+sat); else q=v+sat-v*sat;\n" +
		"  float p=2.0*v-q;\n" +
		"  float tr=hue+0.3333333; if(tr>1.0) tr-=1.0; float tb=hue-0.3333333; if(tb<0.0) tb+=1.0;\n" +
		"  if(6.0*tr<1.0) r=p+(q-p)*6.0*tr; else if(2.0*tr<1.0) r=q; else if(3.0*tr<2.0) r=p+(q-p)*(0.6666666-tr)*6.0; else r=p;\n" +
		"  if(6.0*hue<1.0) g=p+(q-p)*6.0*hue; else if(2.0*hue<1.0) g=q; else if(3.0*hue<2.0) g=p+(q-p)*(0.6666666-hue)*6.0; else g=p;\n" +
		"  if(6.0*tb<1.0) b=p+(q-p)*6.0*tb; else if(2.0*tb<1.0) b=q; else if(3.0*tb<2.0) b=p+(q-p)*(0.6666666-tb)*6.0; else b=p;\n" +
		"  return vec3(r,g,b);\n" +
		"}\n";

	private static final String SCENE_VERT =
		"#version 330\n" +
		"layout(location=0) in vec3 aPos;\n" +
		"layout(location=1) in int aHsl;\n" +
		"uniform mat4 proj;\n" +
		"out vec3 vColor;\n" + HSL_TO_RGB +
		"void main(){\n" +
		"  gl_Position = proj * vec4(aPos, 1.0);\n" +
		"  vec3 hsl = vec3(float((aHsl>>10)&0x3f), float((aHsl>>7)&0x7), float(aHsl&0x7f));\n" +
		"  vColor = hslToRgb(hsl);\n" +
		"}\n";

	private static final String SCENE_FRAG =
		"#version 330\n" +
		"in vec3 vColor;\nout vec4 FragColor;\n" +
		"void main(){ FragColor = vec4(vColor, 1.0); }\n";

	private static final String UI_VERT =
		"#version 330\nout vec2 vUv;\n" +
		"void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2)); vUv=vec2(p.x,1.0-p.y); gl_Position=vec4(p*2.0-1.0,0.0,1.0); }\n";
	private static final String UI_FRAG =
		"#version 330\nuniform sampler2D tex;\nin vec2 vUv;\nout vec4 FragColor;\n" +
		"void main(){ FragColor = texture(tex, vUv); }\n";

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private DrawManager drawManager;

	private Canvas canvas;
	private AWTContext awtContext;
	private GLCapabilities glCapabilities;
	private volatile boolean glReady;

	private int uiProgram, uniUiTex, uiVao, uiTexture;
	private int sceneProgram, uniSceneProj, sceneVao, sceneVbo;

	private final ByteBuffer geom = BufferUtils.createByteBuffer(GEOM_BYTES);
	private int vertexCount;
	private int cntPaint, cntTile, cntModel, cntPre;
	private long lastDiagLog;
	private final float[] proj = Mat4.identity();
	private int[] mvx = new int[0], mvy = new int[0], mvz = new int[0];

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			try
			{
				AWTContext.loadNatives();
				canvas = client.getCanvas();
				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid()) return false;
					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}
				awtContext.createGLContext();
				canvas.setIgnoreRepaint(true);
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");
				glCapabilities = GL.createCapabilities();
				if (!glCapabilities.OpenGL45) throw new RuntimeException("OpenGL 4.5 required");
				glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // reversed-Z: 1 near, 0 far

				uiProgram = compile(UI_VERT, UI_FRAG);
				uniUiTex = glGetUniformLocation(uiProgram, "tex");
				uiVao = glGenVertexArrays();
				uiTexture = glGenTextures();
				glBindTexture(GL_TEXTURE_2D, uiTexture);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
				glBindTexture(GL_TEXTURE_2D, 0);

				sceneProgram = compile(SCENE_VERT, SCENE_FRAG);
				uniSceneProj = glGetUniformLocation(sceneProgram, "proj");
				sceneVbo = glGenBuffers();
				sceneVao = glGenVertexArrays();
				glBindVertexArray(sceneVao);
				glBindBuffer(GL_ARRAY_BUFFER, sceneVbo);
				glBufferData(GL_ARRAY_BUFFER, GEOM_BYTES, GL_STREAM_DRAW);
				glEnableVertexAttribArray(0);
				glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0L);
				glEnableVertexAttribArray(1);
				glVertexAttribIPointer(1, 1, GL_INT, 16, 12L);
				glBindVertexArray(0);
				glBindBuffer(GL_ARRAY_BUFFER, 0);

				awtContext.setSwapInterval(1);
				glReady = true;
				client.setDrawCallbacks(this);
				client.setGpuFlags(DrawCallbacks.GPU);
				client.setExpandedMapLoading(0);
				log.info("DayNightRenderer M2a active (terrain)");
			}
			catch (Throwable t)
			{
				log.error("DayNightRenderer init failed", t);
			}
			return true;
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			client.setExpandedMapLoading(0);
			glReady = false;
			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}
			return true;
		});
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		float[] m = Mat4.scale(client.getScale(), client.getScale(), 1);
		Mat4.mul(m, Mat4.projection(client.getViewportWidth(), client.getViewportHeight(), 50));
		Mat4.mul(m, Mat4.rotateX(cameraPitch));
		Mat4.mul(m, Mat4.rotateY(cameraYaw));
		Mat4.mul(m, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		System.arraycopy(m, 0, proj, 0, 16);
		geom.clear();
		vertexCount = 0;
		cntPre++;
	}

	private void vtx(int x, int y, int z, int hsl)
	{
		if (geom.remaining() < 16) return;
		geom.putFloat(x).putFloat(y).putFloat(z).putInt(hsl);
		vertexCount++;
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		if (!glReady) return;
		cntPaint++;
		int swc = paint.getSwColor(), sec = paint.getSeColor(), nec = paint.getNeColor(), nwc = paint.getNwColor();
		if ((swc | sec | nec | nwc) == 0) return;
		int[][][] h = scene.getTileHeights();
		int sw = h[plane][tileX][tileZ];
		int se = h[plane][tileX + 1][tileZ];
		int ne = h[plane][tileX + 1][tileZ + 1];
		int nw = h[plane][tileX][tileZ + 1];
		int lx = tileX * TILE, lz = tileZ * TILE;
		// tri 1: SW, SE, NE
		vtx(lx, sw, lz, swc);
		vtx(lx + TILE, se, lz, sec);
		vtx(lx + TILE, ne, lz + TILE, nec);
		// tri 2: SW, NE, NW
		vtx(lx, sw, lz, swc);
		vtx(lx + TILE, ne, lz + TILE, nec);
		vtx(lx, nw, lz + TILE, nwc);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
	{
		if (!glReady) return;
		cntTile++;
		try
		{
			int[] fx = model.getFaceX(), fy = model.getFaceY(), fz = model.getFaceZ();
			int[] vx = model.getVertexX(), vy = model.getVertexY(), vz = model.getVertexZ();
			int[] ca = model.getTriangleColorA(), cb = model.getTriangleColorB(), cc = model.getTriangleColorC();
			if (fx == null || fy == null || fz == null || vx == null || vy == null || vz == null || ca == null || cb == null || cc == null) return;
			for (int i = 0; i < fx.length; i++)
			{
				if (ca[i] == 12345678) continue;
				int v0 = fx[i], v1 = fy[i], v2 = fz[i];
				if (v0 < 0 || v1 < 0 || v2 < 0 || v0 >= vx.length || v1 >= vx.length || v2 >= vx.length) continue;
				vtx(vx[v0], vy[v0], vz[v0], ca[i]);
				vtx(vx[v1], vy[v1], vz[v1], cb[i]);
				vtx(vx[v2], vy[v2], vz[v2], cc[i]);
			}
		}
		catch (Throwable t) { logDrawError("tileModel", t); }
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		if (!glReady) return;
		try
		{
			Model model = renderable.getModel();
			if (model == null) return;
			cntModel++;
			float[] vxA = model.getVerticesX(), vyA = model.getVerticesY(), vzA = model.getVerticesZ();
			int[] i1 = model.getFaceIndices1(), i2 = model.getFaceIndices2(), i3 = model.getFaceIndices3();
			int[] c1 = model.getFaceColors1(), c2 = model.getFaceColors2(), c3 = model.getFaceColors3();
			if (vxA == null || vyA == null || vzA == null || i1 == null || i2 == null || i3 == null || c1 == null || c2 == null || c3 == null) return;
			int vc = vxA.length;
			if (mvx.length < vc) { mvx = new int[vc]; mvy = new int[vc]; mvz = new int[vc]; }
			int o = orientation & 2047;
			int oSin = o != 0 ? Perspective.SINE[o] : 0;
			int oCos = o != 0 ? Perspective.COSINE[o] : 0;
			for (int v = 0; v < vc; v++)
			{
				int px = (int) vxA[v], py = (int) vyA[v], pz = (int) vzA[v];
				if (o != 0) { int x0 = px; px = (pz * oSin + x0 * oCos) >> 16; pz = (pz * oCos - x0 * oSin) >> 16; }
				mvx[v] = px + x; mvy[v] = py + y; mvz[v] = pz + z;
			}
			int fcount = i1.length;
			for (int f = 0; f < fcount; f++)
			{
				int col3 = c3[f];
				if (col3 == -2) continue;
				int col1 = c1[f], col2 = c2[f];
				if (col3 == -1) { col2 = col1; col3 = col1; }
				int a = i1[f], b = i2[f], cI = i3[f];
				if (a < 0 || b < 0 || cI < 0 || a >= vc || b >= vc || cI >= vc) continue;
				vtx(mvx[a], mvy[a], mvz[a], col1);
				vtx(mvx[b], mvy[b], mvz[b], col2);
				vtx(mvx[cI], mvy[cI], mvz[cI], col3);
			}
		}
		catch (Throwable t) { logDrawError("model", t); }
	}

	private boolean loggedDrawError;
	private void logDrawError(String what, Throwable t)
	{
		if (!loggedDrawError) { loggedDrawError = true; log.warn("DayNightRenderer draw error ({})", what, t); }
	}

	@Override
	public void draw(int overlayColor)
	{
		if (!glReady || awtContext == null) return;

		long nowt = System.currentTimeMillis();
		if (nowt - lastDiagLog > 2000) { lastDiagLog = nowt; log.info("DNR gs={} pre={} verts={} paint={} tile={} model={}", client.getGameState(), cntPre, vertexCount, cntPaint, cntTile, cntModel); cntPre=0; cntPaint=0; cntTile=0; cntModel=0; }
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glViewport(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		glClearColor(0f, 0f, 0f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		// --- 3D scene ---
		if (vertexCount > 0)
		{
			geom.flip();
			glBindBuffer(GL_ARRAY_BUFFER, sceneVbo);
			glBufferSubData(GL_ARRAY_BUFFER, 0, geom);
			glBindBuffer(GL_ARRAY_BUFFER, 0);

			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_GREATER);
			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);
			glUseProgram(sceneProgram);
			glUniformMatrix4fv(uniSceneProj, false, proj);
			glBindVertexArray(sceneVao);
			glDrawArrays(GL_TRIANGLES, 0, vertexCount);
			glBindVertexArray(0);
			glUseProgram(0);
			glDisable(GL_DEPTH_TEST);
		}

		// --- UI on top ---
		BufferProvider bp = client.getBufferProvider();
		int[] pixels = bp.getPixels();
		int w = bp.getWidth(), hh = bp.getHeight();
		if (pixels != null && w > 0 && hh > 0 && pixels.length >= w * hh)
		{
			IntBuffer buf = BufferUtils.createIntBuffer(w * hh);
			buf.put(pixels, 0, w * hh).flip();
			glBindTexture(GL_TEXTURE_2D, uiTexture);
			glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, hh, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf);
			glEnable(GL_BLEND);
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			glUseProgram(uiProgram);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, uiTexture);
			glUniform1i(uniUiTex, 0);
			glBindVertexArray(uiVao);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glBindVertexArray(0);
			glUseProgram(0);
			glDisable(GL_BLEND);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		awtContext.swapBuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
	}

	@Override public void swapScene(Scene scene) { }
	@Override public void loadScene(Scene scene) { }
	@Override public void loadScene(WorldView worldView, Scene scene) { }

	private static int compile(String vsrc, String fsrc)
	{
		int vs = glCreateShader(GL_VERTEX_SHADER);
		glShaderSource(vs, vsrc); glCompileShader(vs);
		if (glGetShaderi(vs, GL_COMPILE_STATUS) != GL_TRUE) throw new RuntimeException("VS: " + glGetShaderInfoLog(vs));
		int fs = glCreateShader(GL_FRAGMENT_SHADER);
		glShaderSource(fs, fsrc); glCompileShader(fs);
		if (glGetShaderi(fs, GL_COMPILE_STATUS) != GL_TRUE) throw new RuntimeException("FS: " + glGetShaderInfoLog(fs));
		int p = glCreateProgram();
		glAttachShader(p, vs); glAttachShader(p, fs); glLinkProgram(p);
		if (glGetProgrami(p, GL_LINK_STATUS) != GL_TRUE) throw new RuntimeException("link: " + glGetProgramInfoLog(p));
		glDeleteShader(vs); glDeleteShader(fs);
		return p;
	}
}
