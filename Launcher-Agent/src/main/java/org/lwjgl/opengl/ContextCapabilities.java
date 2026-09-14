package org.lwjgl.opengl;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.LWJGLException;

/**
 * {@code ContextCapabilities} de LWJGL 2 au-dessus de {@link GLCapabilities}
 * de LWJGL 3.
 *
 * <p>RÉÉCRIT pour YuyuFrame. L'original de legacy-lwjgl3 (6 788 lignes)
 * déclarait toutes les extensions de LWJGL 2 puis recopiait les valeurs de
 * {@code GLCapabilities} champ par champ PAR RÉFLEXION — exclu par D4.
 *
 * <p>Ici : exactement les 95 champs que lit Minecraft 1.8.9 (inventaire
 * bytecode du jar vanilla, 2026-09-14), recopiés en accès typé. Sept d'entre
 * eux n'existent plus dans {@code GLCapabilities} 3.4.1 (extensions
 * abandonnées) : ils sont lus dans la liste d'extensions du pilote, comme le
 * faisait {@code GLCapabilitiesMixin} de legacy-lwjgl3 — mixin devenu inutile.
 *
 * <p>Un champ LWJGL 2 absent d'ici et lu par un futur code donnerait un
 * {@code NoSuchFieldError} franc : c'est voulu, plutôt qu'un {@code false}
 * silencieux.
 */
@SuppressWarnings("unused")
public class ContextCapabilities {

    /** Lu par {@link Util#checkGLError()} ; {@code false} comme dans l'original. */
    static final boolean DEBUG = false;

    public boolean OpenGL13;
    public boolean OpenGL14;
    public boolean OpenGL15;
    public boolean OpenGL20;
    public boolean OpenGL21;
    public boolean OpenGL30;

    public boolean GL_ARB_arrays_of_arrays;
    public boolean GL_ARB_base_instance;
    public boolean GL_ARB_blend_func_extended;
    public boolean GL_ARB_clear_buffer_object;
    public boolean GL_ARB_color_buffer_float;
    public boolean GL_ARB_compatibility;
    public boolean GL_ARB_compressed_texture_pixel_storage;
    public boolean GL_ARB_compute_shader;
    public boolean GL_ARB_copy_buffer;
    public boolean GL_ARB_copy_image;
    public boolean GL_ARB_depth_buffer_float;
    public boolean GL_ARB_depth_clamp;
    public boolean GL_ARB_depth_texture;
    public boolean GL_ARB_draw_buffers;
    public boolean GL_ARB_draw_buffers_blend;
    public boolean GL_ARB_draw_elements_base_vertex;
    public boolean GL_ARB_draw_indirect;
    public boolean GL_ARB_draw_instanced;
    public boolean GL_ARB_explicit_attrib_location;
    public boolean GL_ARB_explicit_uniform_location;
    public boolean GL_ARB_fragment_layer_viewport;
    public boolean GL_ARB_fragment_program;
    public boolean GL_ARB_fragment_program_shadow;
    public boolean GL_ARB_fragment_shader;
    public boolean GL_ARB_framebuffer_object;
    public boolean GL_ARB_framebuffer_sRGB;
    public boolean GL_ARB_geometry_shader4;
    public boolean GL_ARB_gpu_shader5;
    public boolean GL_ARB_half_float_pixel;
    public boolean GL_ARB_half_float_vertex;
    public boolean GL_ARB_instanced_arrays;
    public boolean GL_ARB_map_buffer_alignment;
    public boolean GL_ARB_map_buffer_range;
    public boolean GL_ARB_multisample;
    public boolean GL_ARB_multitexture;
    public boolean GL_ARB_occlusion_query2;
    public boolean GL_ARB_pixel_buffer_object;
    public boolean GL_ARB_seamless_cube_map;
    public boolean GL_ARB_shader_objects;
    public boolean GL_ARB_shader_stencil_export;
    public boolean GL_ARB_shader_texture_lod;
    public boolean GL_ARB_shadow;
    public boolean GL_ARB_shadow_ambient;
    public boolean GL_ARB_stencil_texturing;
    public boolean GL_ARB_sync;
    public boolean GL_ARB_tessellation_shader;
    public boolean GL_ARB_texture_border_clamp;
    public boolean GL_ARB_texture_buffer_object;
    public boolean GL_ARB_texture_cube_map;
    public boolean GL_ARB_texture_cube_map_array;
    public boolean GL_ARB_texture_env_combine;
    public boolean GL_ARB_texture_non_power_of_two;
    public boolean GL_ARB_uniform_buffer_object;
    public boolean GL_ARB_vertex_blend;
    public boolean GL_ARB_vertex_buffer_object;
    public boolean GL_ARB_vertex_program;
    public boolean GL_ARB_vertex_shader;

    public boolean GL_EXT_bindable_uniform;
    public boolean GL_EXT_blend_equation_separate;
    public boolean GL_EXT_blend_func_separate;
    public boolean GL_EXT_blend_minmax;
    public boolean GL_EXT_blend_subtract;
    public boolean GL_EXT_draw_instanced;
    public boolean GL_EXT_framebuffer_multisample;
    public boolean GL_EXT_framebuffer_object;
    public boolean GL_EXT_framebuffer_sRGB;
    public boolean GL_EXT_geometry_shader4;
    public boolean GL_EXT_gpu_program_parameters;
    public boolean GL_EXT_gpu_shader4;
    public boolean GL_EXT_multi_draw_arrays;
    public boolean GL_EXT_packed_depth_stencil;
    public boolean GL_EXT_paletted_texture;
    public boolean GL_EXT_rescale_normal;
    public boolean GL_EXT_separate_shader_objects;
    public boolean GL_EXT_shader_image_load_store;
    public boolean GL_EXT_shadow_funcs;
    public boolean GL_EXT_shared_texture_palette;
    public boolean GL_EXT_stencil_clear_tag;
    public boolean GL_EXT_stencil_two_side;
    public boolean GL_EXT_stencil_wrap;
    public boolean GL_EXT_texture_3d;
    public boolean GL_EXT_texture_array;
    public boolean GL_EXT_texture_buffer_object;
    public boolean GL_EXT_texture_integer;
    public boolean GL_EXT_texture_lod_bias;
    public boolean GL_EXT_texture_sRGB;
    public boolean GL_EXT_vertex_shader;
    public boolean GL_EXT_vertex_weighting;

    public boolean GL_NV_fog_distance;

    /**
     * Même signature que l'original (appelé par {@link GLContext}). Le
     * contexte doit être courant : {@code GL.getCapabilities()} lève sinon,
     * converti en {@link LWJGLException} comme en LWJGL 2.
     */
    ContextCapabilities(boolean forwardCompatible) throws LWJGLException {
        GLCapabilities c;
        try {
            c = GL.getCapabilities();
        } catch (IllegalStateException e) {
            throw new LWJGLException("No OpenGL context is current", e);
        }

        OpenGL13 = c.OpenGL13;
        OpenGL14 = c.OpenGL14;
        OpenGL15 = c.OpenGL15;
        OpenGL20 = c.OpenGL20;
        OpenGL21 = c.OpenGL21;
        OpenGL30 = c.OpenGL30;

        GL_ARB_arrays_of_arrays = c.GL_ARB_arrays_of_arrays;
        GL_ARB_base_instance = c.GL_ARB_base_instance;
        GL_ARB_blend_func_extended = c.GL_ARB_blend_func_extended;
        GL_ARB_clear_buffer_object = c.GL_ARB_clear_buffer_object;
        GL_ARB_color_buffer_float = c.GL_ARB_color_buffer_float;
        GL_ARB_compatibility = c.GL_ARB_compatibility;
        GL_ARB_compressed_texture_pixel_storage = c.GL_ARB_compressed_texture_pixel_storage;
        GL_ARB_compute_shader = c.GL_ARB_compute_shader;
        GL_ARB_copy_buffer = c.GL_ARB_copy_buffer;
        GL_ARB_copy_image = c.GL_ARB_copy_image;
        GL_ARB_depth_buffer_float = c.GL_ARB_depth_buffer_float;
        GL_ARB_depth_clamp = c.GL_ARB_depth_clamp;
        GL_ARB_depth_texture = c.GL_ARB_depth_texture;
        GL_ARB_draw_buffers = c.GL_ARB_draw_buffers;
        GL_ARB_draw_buffers_blend = c.GL_ARB_draw_buffers_blend;
        GL_ARB_draw_elements_base_vertex = c.GL_ARB_draw_elements_base_vertex;
        GL_ARB_draw_indirect = c.GL_ARB_draw_indirect;
        GL_ARB_draw_instanced = c.GL_ARB_draw_instanced;
        GL_ARB_explicit_attrib_location = c.GL_ARB_explicit_attrib_location;
        GL_ARB_explicit_uniform_location = c.GL_ARB_explicit_uniform_location;
        GL_ARB_fragment_layer_viewport = c.GL_ARB_fragment_layer_viewport;
        GL_ARB_fragment_program = c.GL_ARB_fragment_program;
        GL_ARB_fragment_program_shadow = c.GL_ARB_fragment_program_shadow;
        GL_ARB_fragment_shader = c.GL_ARB_fragment_shader;
        GL_ARB_framebuffer_object = c.GL_ARB_framebuffer_object;
        GL_ARB_framebuffer_sRGB = c.GL_ARB_framebuffer_sRGB;
        GL_ARB_geometry_shader4 = c.GL_ARB_geometry_shader4;
        GL_ARB_gpu_shader5 = c.GL_ARB_gpu_shader5;
        GL_ARB_half_float_pixel = c.GL_ARB_half_float_pixel;
        GL_ARB_half_float_vertex = c.GL_ARB_half_float_vertex;
        GL_ARB_instanced_arrays = c.GL_ARB_instanced_arrays;
        GL_ARB_map_buffer_alignment = c.GL_ARB_map_buffer_alignment;
        GL_ARB_map_buffer_range = c.GL_ARB_map_buffer_range;
        GL_ARB_multisample = c.GL_ARB_multisample;
        GL_ARB_multitexture = c.GL_ARB_multitexture;
        GL_ARB_occlusion_query2 = c.GL_ARB_occlusion_query2;
        GL_ARB_pixel_buffer_object = c.GL_ARB_pixel_buffer_object;
        GL_ARB_seamless_cube_map = c.GL_ARB_seamless_cube_map;
        GL_ARB_shader_objects = c.GL_ARB_shader_objects;
        GL_ARB_shader_stencil_export = c.GL_ARB_shader_stencil_export;
        GL_ARB_shader_texture_lod = c.GL_ARB_shader_texture_lod;
        GL_ARB_shadow = c.GL_ARB_shadow;
        GL_ARB_shadow_ambient = c.GL_ARB_shadow_ambient;
        GL_ARB_stencil_texturing = c.GL_ARB_stencil_texturing;
        GL_ARB_sync = c.GL_ARB_sync;
        GL_ARB_tessellation_shader = c.GL_ARB_tessellation_shader;
        GL_ARB_texture_border_clamp = c.GL_ARB_texture_border_clamp;
        GL_ARB_texture_buffer_object = c.GL_ARB_texture_buffer_object;
        GL_ARB_texture_cube_map = c.GL_ARB_texture_cube_map;
        GL_ARB_texture_cube_map_array = c.GL_ARB_texture_cube_map_array;
        GL_ARB_texture_env_combine = c.GL_ARB_texture_env_combine;
        GL_ARB_texture_non_power_of_two = c.GL_ARB_texture_non_power_of_two;
        GL_ARB_uniform_buffer_object = c.GL_ARB_uniform_buffer_object;
        GL_ARB_vertex_blend = c.GL_ARB_vertex_blend;
        GL_ARB_vertex_buffer_object = c.GL_ARB_vertex_buffer_object;
        GL_ARB_vertex_program = c.GL_ARB_vertex_program;
        GL_ARB_vertex_shader = c.GL_ARB_vertex_shader;

        GL_EXT_bindable_uniform = c.GL_EXT_bindable_uniform;
        GL_EXT_blend_equation_separate = c.GL_EXT_blend_equation_separate;
        GL_EXT_blend_func_separate = c.GL_EXT_blend_func_separate;
        GL_EXT_blend_minmax = c.GL_EXT_blend_minmax;
        GL_EXT_blend_subtract = c.GL_EXT_blend_subtract;
        GL_EXT_draw_instanced = c.GL_EXT_draw_instanced;
        GL_EXT_framebuffer_multisample = c.GL_EXT_framebuffer_multisample;
        GL_EXT_framebuffer_object = c.GL_EXT_framebuffer_object;
        GL_EXT_framebuffer_sRGB = c.GL_EXT_framebuffer_sRGB;
        GL_EXT_geometry_shader4 = c.GL_EXT_geometry_shader4;
        GL_EXT_gpu_program_parameters = c.GL_EXT_gpu_program_parameters;
        GL_EXT_gpu_shader4 = c.GL_EXT_gpu_shader4;
        GL_EXT_packed_depth_stencil = c.GL_EXT_packed_depth_stencil;
        GL_EXT_separate_shader_objects = c.GL_EXT_separate_shader_objects;
        GL_EXT_shader_image_load_store = c.GL_EXT_shader_image_load_store;
        GL_EXT_shadow_funcs = c.GL_EXT_shadow_funcs;
        GL_EXT_shared_texture_palette = c.GL_EXT_shared_texture_palette;
        GL_EXT_stencil_clear_tag = c.GL_EXT_stencil_clear_tag;
        GL_EXT_stencil_two_side = c.GL_EXT_stencil_two_side;
        GL_EXT_stencil_wrap = c.GL_EXT_stencil_wrap;
        GL_EXT_texture_array = c.GL_EXT_texture_array;
        GL_EXT_texture_buffer_object = c.GL_EXT_texture_buffer_object;
        GL_EXT_texture_integer = c.GL_EXT_texture_integer;
        GL_EXT_texture_sRGB = c.GL_EXT_texture_sRGB;

        GL_NV_fog_distance = c.GL_NV_fog_distance;

        // Les sept extensions abandonnées par LWJGL 3 : lues dans la liste du pilote.
        Set<String> extensions = driverExtensions();
        GL_EXT_multi_draw_arrays = extensions.contains("GL_EXT_multi_draw_arrays");
        GL_EXT_paletted_texture = extensions.contains("GL_EXT_paletted_texture");
        GL_EXT_rescale_normal = extensions.contains("GL_EXT_rescale_normal");
        GL_EXT_texture_3d = extensions.contains("GL_EXT_texture_3d");
        GL_EXT_texture_lod_bias = extensions.contains("GL_EXT_texture_lod_bias");
        GL_EXT_vertex_shader = extensions.contains("GL_EXT_vertex_shader");
        GL_EXT_vertex_weighting = extensions.contains("GL_EXT_vertex_weighting");
    }

    /**
     * Extensions annoncées par le contexte courant : {@code glGetStringi} à
     * partir de GL 3.0, chaîne unique séparée par des espaces avant.
     */
    private static Set<String> driverExtensions() {
        Set<String> out = new HashSet<>();
        String version = GL11.glGetString(GL11.GL_VERSION);
        int major = 0;
        if (version != null && !version.isEmpty() && Character.isDigit(version.charAt(0))) {
            major = version.charAt(0) - '0';
        }
        if (major >= 3) {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String name = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (name != null) out.add(name);
            }
        } else {
            String all = GL11.glGetString(GL11.GL_EXTENSIONS);
            if (all != null) {
                for (String name : all.split(" ")) {
                    if (!name.isEmpty()) out.add(name);
                }
            }
        }
        return out;
    }
}
