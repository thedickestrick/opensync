package com.opensync.foldersync.videoedit

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.opensync.foldersync.ColorEdits
import com.opensync.foldersync.colorMatrixFor
import android.graphics.ColorMatrix as GfxColorMatrix

/** Every tone value the shader reads, bundled so it can be swapped atomically between frames. */
internal class ToneParams(
    val colorMatrix: FloatArray,
    val exposure: Float,
    val highlights: Float,
    val shadows: Float,
    val sharpness: Float,
    val vignette: Float
) {
    companion object {
        fun from(color: ColorEdits, tone: ToneEdits) = ToneParams(
            colorMatrix = glRgbMatrix(colorMatrixFor(color)),
            exposure = tone.exposure,
            highlights = tone.highlights,
            shadows = tone.shadows,
            sharpness = tone.sharpness,
            vignette = tone.vignette
        )
    }
}

/**
 * All light & colour work in one GPU pass: sharpen/soften, exposure, highlights, shadows, the
 * shared colour matrix (brightness, contrast, saturation, warmth, tint, filter) and a vignette.
 * [params] is read on every frame, so the preview follows the sliders without rebuilding the
 * effect pipeline.
 */
@OptIn(UnstableApi::class)
internal class ToneEffect(@Volatile var params: ToneParams) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = ToneShaderProgram(this, useHdr)
}

@OptIn(UnstableApi::class)
private class ToneShaderProgram(private val effect: ToneEffect, private val useHdr: Boolean) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var texelW = 0f
    private var texelH = 0f
    private var aspect = 1f

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelW = 1f / inputWidth
        texelH = 1f / inputHeight
        aspect = inputWidth.toFloat() / inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val p = effect.params
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatsUniform("uTexelSize", floatArrayOf(texelW, texelH))
            program.setFloatUniform("uAspect", aspect)
            program.setFloatsUniform("uColorMatrix", p.colorMatrix)
            program.setFloatUniform("uExposure", p.exposure)
            program.setFloatUniform("uHighlights", p.highlights)
            program.setFloatUniform("uShadows", p.shadows)
            program.setFloatUniform("uSharpness", p.sharpness)
            program.setFloatUniform("uVignette", p.vignette)
            program.setFloatUniform("uLinearInput", if (useHdr) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        // SDR colours arrive gamma-encoded (Media3's default SDR working space), the space the photo
        // editor's colour matrix works in. HDR arrives linear (the preview keeps HDR; saving tone-maps
        // to SDR first), so it is gamma-encoded around the adjustments to look the same.
        const val FRAGMENT_SHADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uAspect;
uniform mat4 uColorMatrix;
uniform float uExposure;
uniform float uHighlights;
uniform float uShadows;
uniform float uSharpness;
uniform float uVignette;
uniform float uLinearInput;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 src = texture2D(uTexSampler, uv);
  vec3 c = src.rgb;
  if (uLinearInput > 0.5) c = pow(max(c, 0.0), vec3(1.0 / 2.2));

  if (uSharpness != 0.0) {
    vec3 blur = (texture2D(uTexSampler, uv + vec2(uTexelSize.x, 0.0)).rgb
        + texture2D(uTexSampler, uv - vec2(uTexelSize.x, 0.0)).rgb
        + texture2D(uTexSampler, uv + vec2(0.0, uTexelSize.y)).rgb
        + texture2D(uTexSampler, uv - vec2(0.0, uTexelSize.y)).rgb) * 0.25;
    float amount = uSharpness > 0.0 ? uSharpness * 1.5 : uSharpness * 0.9;
    c = c + amount * (c - blur);
  }

  if (uExposure != 0.0) {
    // In (approximately) linear light, like a camera exposure change: +/-1 is +/-2 stops.
    vec3 lin = pow(max(c, 0.0), vec3(2.2)) * exp2(uExposure * 2.0);
    c = pow(lin, vec3(1.0 / 2.2));
  }

  c = clamp(c, 0.0, 1.0);
  float l = luma(c);
  c += uShadows * 0.35 * (1.0 - smoothstep(0.0, 0.55, l));
  c += uHighlights * 0.35 * smoothstep(0.45, 1.0, l);
  c = clamp(c, 0.0, 1.0);

  c = (uColorMatrix * vec4(c, 1.0)).rgb;

  if (uVignette != 0.0) {
    vec2 d = uv - 0.5;
    d.x *= uAspect;
    float r = length(d) / length(vec2(0.5 * uAspect, 0.5));
    float v = smoothstep(0.3, 1.0, r);
    c = uVignette > 0.0 ? c * (1.0 - uVignette * 0.85 * v) : mix(c, vec3(1.0), -uVignette * 0.7 * v);
  }

  c = clamp(c, 0.0, 1.0);
  if (uLinearInput > 0.5) c = pow(c, vec3(2.2));

  gl_FragColor = vec4(c, src.a);
}
"""
    }
}

/**
 * Converts an Android 4x5 colour matrix (0..255 offsets) into the column-major 4x4 GL matrix the
 * shader applies to 0..1 RGB, so video gets exactly the photo editor's look.
 */
internal fun glRgbMatrix(cm: GfxColorMatrix): FloatArray {
    val a = cm.array
    val m = FloatArray(16)
    for (r in 0..2) {
        for (c in 0..2) m[c * 4 + r] = a[r * 5 + c]
        m[12 + r] = a[r * 5 + 3] + a[r * 5 + 4] / 255f // frames are opaque: fold alpha into the offset
    }
    m[15] = 1f
    return m
}
