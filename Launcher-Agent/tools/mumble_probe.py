"""Lit la memoire partagee MumbleLink depuis un AUTRE processus, comme Mumble.

Reproduit plugins/link/SharedMemory.cpp : OpenFileMappingW puis MapViewOfFile,
et decode la structure LinkedMem. Deux lectures espacees d'une seconde pour
voir si uiTick bouge — c'est la condition d'activation du greffon Link.
"""
import ctypes, struct, sys, time
from ctypes import wintypes

k32 = ctypes.WinDLL("kernel32", use_last_error=True)

FILE_MAP_READ = 0x0004
SIZE = 5460

k32.OpenFileMappingW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
k32.OpenFileMappingW.restype = wintypes.HANDLE
k32.MapViewOfFile.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD,
                              wintypes.DWORD, ctypes.c_size_t]
k32.MapViewOfFile.restype = ctypes.c_void_p

h = k32.OpenFileMappingW(FILE_MAP_READ, False, "MumbleLink")
if not h:
    err = ctypes.get_last_error()
    print(f"OpenFileMapping ECHEC : GetLastError={err}")
    print("  2   = ERROR_FILE_NOT_FOUND : aucun processus n'a cree l'objet")
    print("  5   = ERROR_ACCESS_DENIED  : cree par un processus d'integrite superieure")
    sys.exit(1)

view = k32.MapViewOfFile(h, FILE_MAP_READ, 0, 0, 0)
if not view:
    print(f"MapViewOfFile ECHEC : GetLastError={ctypes.get_last_error()}")
    sys.exit(1)


def snapshot():
    return ctypes.string_at(view, SIZE)


def wstr(b, off, n):
    raw = b[off:off + n * 2]
    s = raw.decode("utf-16-le", "replace")
    return s.split("\x00", 1)[0]


a = snapshot()
time.sleep(1.0)
b = snapshot()

ver_a, tick_a = struct.unpack_from("<II", a, 0)
ver_b, tick_b = struct.unpack_from("<II", b, 0)
pos = struct.unpack_from("<3f", b, 8)
front = struct.unpack_from("<3f", b, 20)
top = struct.unpack_from("<3f", b, 32)
cam = struct.unpack_from("<3f", b, 556)
ctx_len = struct.unpack_from("<I", b, 1104)[0]
ctx = b[1108:1108 + min(ctx_len, 256)]

print("Memoire partagee MumbleLink LUE depuis un autre processus.\n")
print(f"  uiVersion    : {ver_b}   (Mumble exige 1 ou 2)")
print(f"  uiTick       : {tick_a} -> {tick_b}   {'BOUGE' if tick_b != tick_a else 'FIGE (Mumble refusera le lien)'}")
print(f"  name         : {wstr(b, 44, 256)!r}")
print(f"  identity     : {wstr(b, 592, 256)!r}")
print(f"  description  : {wstr(b, 1364, 2048)!r}")
print(f"  context_len  : {ctx_len}")
print(f"  context      : {ctx!r}")
print(f"  avatar pos   : {pos}")
print(f"  avatar front : {front}")
print(f"  avatar top   : {top}")
print(f"  camera pos   : {cam}")

ok = ver_b in (1, 2) and tick_b != tick_a
print("\n=> " + ("Cote jeu CONFORME : Mumble a tout ce qu'il lui faut."
                 if ok else "Cote jeu NON conforme (voir uiVersion/uiTick ci-dessus)."))
