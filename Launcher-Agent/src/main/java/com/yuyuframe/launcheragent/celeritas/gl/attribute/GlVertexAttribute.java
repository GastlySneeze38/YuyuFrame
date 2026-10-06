package com.yuyuframe.launcheragent.celeritas.gl.attribute;

public class GlVertexAttribute {
    private final GlVertexAttributeFormat format;
    private final int count;
    private final int pointer;
    private final int size;
    private final int stride;

    private final boolean normalized;
    private final boolean intType;

    private final String name;

    /**
     * @param format The format used
     * @param count The number of components in the vertex attribute
     * @param normalized Specifies whether or not fixed-point data values should be normalized (true) or used directly
 *                   as fixed-point values (false)
     * @param pointer The offset to the first component in the attribute
     */
    public GlVertexAttribute(GlVertexAttributeFormat format, String name, int count, boolean normalized, int pointer, int stride, boolean intType) {
        this(format, format.size() * count, count, name, normalized, pointer, stride, intType);
    }

    protected GlVertexAttribute(GlVertexAttributeFormat format, int size, int count, String name, boolean normalized, int pointer, int stride, boolean intType) {
        this.format = format;
        this.size = size;
        this.count = count;
        this.normalized = normalized;
        this.pointer = pointer;
        this.stride = stride;
        this.intType = intType;
        this.name = name;
    }

    public GlVertexAttributeFormat getFormat() {
        return this.format;
    }

    public int getCount() {
        return this.count;
    }

    public int getPointer() {
        return this.pointer;
    }

    public int getSize() {
        return this.size;
    }

    public int getStride() {
        return this.stride;
    }

    public String getName() {
        return this.name;
    }

    public boolean isNormalized() {
        return this.normalized;
    }

    public boolean isIntType() {
        return this.intType;
    }
}
