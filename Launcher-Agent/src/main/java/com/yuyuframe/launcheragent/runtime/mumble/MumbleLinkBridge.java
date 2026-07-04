package com.yuyuframe.launcheragent.runtime.mumble;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Pont Mumble Link (protocole officiel v2, mémoire partagée Win32) — port
 * direct de PvP-Mod {@code MumbleLinkHandler}/{@code MumbleLinkConfig},
 * débarrassé de Forge (pas de {@code TickEvent}/{@code @SubscribeEvent} :
 * ici c'est {@code MumbleLinkModule.onTick()} — ModuleRegistry, même
 * mécanisme que FovModule/CoordsModule — qui appelle {@link #update} chaque
 * frame, pas un event bus).
 *
 * Aucun code natif à nous : JNA (jna.jar/jna-platform.jar, voir build.bat)
 * appelle directement kernel32.dll (CreateFileMappingA/MapViewOfFile), même
 * principe que BorderlessWindowNative pour User32 — pas de nouvelle DLL Rust,
 * contrairement à content_core.dll/rust_core.dll.
 */
public final class MumbleLinkBridge {

    private MumbleLinkBridge() {}

    // Taille de struct LinkedMem (spec Mumble v2) :
    // 4+4 + 3×12 + 512 + 3×12 + 3×12 + 512 + 4 + 256 + 4096 = 5460 bytes
    private static final int SIZE = 5460;
    private static final int PAGE_READWRITE = 0x04;
    private static final int FILE_MAP_WRITE = 0x0002;
    private static final Pointer INVALID = Pointer.createConstant(-1L);

    private interface Kernel32 extends Library {
        Pointer CreateFileMappingA(Pointer hFile, Pointer lpSec, int flProtect,
                                    int sizeHigh, int sizeLow, String name);
        Pointer MapViewOfFile(Pointer hMap, int access, int offHigh, int offLow, int bytes);
        boolean UnmapViewOfFile(Pointer lp);
        boolean CloseHandle(Pointer h);
    }

    private static Kernel32 k32;
    private static Pointer hMap;
    private static Pointer view;
    private static ByteBuffer buf;
    private static int tick = 0;
    private static boolean ready = false;
    private static boolean failed = false;

    public static boolean isReady() { return ready; }

    /** Tente l'initialisation si pas déjà fait/échoué — sûr à appeler à chaque frame (voir MumbleLinkModule.onTick). */
    public static synchronized boolean ensureInit() {
        if (ready) return true;
        if (failed) return false;
        return tryInit();
    }

    private static boolean tryInit() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            failed = true;
            LauncherLog.warn("[MumbleLink] OS non-Windows — indisponible (mémoire partagée Win32 uniquement)");
            return false;
        }
        try {
            k32 = Native.load("kernel32", Kernel32.class);
            hMap = k32.CreateFileMappingA(INVALID, null, PAGE_READWRITE, 0, SIZE, "MumbleLink");
            if (hMap == null || Pointer.nativeValue(hMap) == 0) { failed = true; return false; }

            view = k32.MapViewOfFile(hMap, FILE_MAP_WRITE, 0, 0, 0);
            if (view == null || Pointer.nativeValue(view) == 0) {
                k32.CloseHandle(hMap);
                failed = true;
                return false;
            }

            buf = view.getByteBuffer(0, SIZE);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // Champs statiques (name/description), écrits une seule fois — voir spec Mumble Link.
            writeUtf16Le("Minecraft", 44, 256);
            writeUtf16Le("YuyuFrame LauncherAgent", 1364, 2048);

            Runtime.getRuntime().addShutdownHook(new Thread(MumbleLinkBridge::shutdown, "mumblelink-shutdown"));
            ready = true;
            LauncherLog.info("[MumbleLink] mémoire partagée initialisée");
            return true;
        } catch (Throwable t) {
            failed = true;
            LauncherLog.warn("[MumbleLink] initialisation échouée : " + t);
            return false;
        }
    }

    /**
     * Écrit position/orientation/nom — appelé CHAQUE frame par
     * {@code MumbleLinkModule.onTick()} tant que le module est actif.
     * Repère Minecraft tel quel (pas de conversion) : Mumble accepte
     * n'importe quel repère cohérent tant qu'avatar/caméra correspondent.
     */
    public static void update(float eyeX, float eyeY, float eyeZ, float yawRad, float pitchRad, String username) {
        if (!ready) return;
        try {
            float fX = -(float) (Math.sin(yawRad) * Math.cos(pitchRad));
            float fY = -(float) Math.sin(pitchRad);
            float fZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));

            buf.putInt(0, 2);       // uiVersion
            buf.putInt(4, ++tick);  // uiTick (doit changer à chaque frame)

            // Avatar position/front/top (offsets 8/20/32)
            buf.putFloat(8, eyeX);  buf.putFloat(12, eyeY); buf.putFloat(16, eyeZ);
            buf.putFloat(20, fX);   buf.putFloat(24, fY);   buf.putFloat(28, fZ);
            buf.putFloat(32, 0f);   buf.putFloat(36, 1f);   buf.putFloat(40, 0f);

            // Caméra = identique à l'avatar en vue première personne (offsets 556/568/580)
            buf.putFloat(556, eyeX); buf.putFloat(560, eyeY); buf.putFloat(564, eyeZ);
            buf.putFloat(568, fX);   buf.putFloat(572, fY);   buf.putFloat(576, fZ);
            buf.putFloat(580, 0f);   buf.putFloat(584, 1f);   buf.putFloat(588, 0f);

            // Identity : nom du joueur (offset 592, wchar_t[256])
            writeUtf16Le(username != null ? username : "", 592, 256);
        } catch (Throwable t) {
            LauncherLog.err("[MumbleLink] update: " + t);
        }
    }

    /** Écrit s en UTF-16LE à l'offset donné, avec null terminator. maxChars = nombre de wchar_t alloués (null terminator compris). */
    private static void writeUtf16Le(String s, int offset, int maxChars) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
        int len = Math.min(bytes.length, (maxChars - 1) * 2);
        for (int i = 0; i < len; i++) buf.put(offset + i, bytes[i]);
        buf.put(offset + len,     (byte) 0);
        buf.put(offset + len + 1, (byte) 0);
    }

    private static void shutdown() {
        try {
            if (view != null) k32.UnmapViewOfFile(view);
            if (hMap != null) k32.CloseHandle(hMap);
        } catch (Throwable ignored) {}
    }
}
