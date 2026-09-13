package net.minecraft.server.packs.resources;

import java.io.InputStream;

/** Stub compile-only (26.1+) — une ressource résolue par le {@link ResourceManager}, packs appliqués. */
public class Resource {
    private Resource() {}
    /** Descripteur vérifié sur le jar 26.1.2 : {@code ()Ljava/io/InputStream;}. À refermer par l'appelant. */
    public InputStream open() { return null; }
}
