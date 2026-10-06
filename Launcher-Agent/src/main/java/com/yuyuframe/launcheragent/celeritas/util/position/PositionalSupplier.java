package com.yuyuframe.launcheragent.celeritas.util.position;

@FunctionalInterface
public interface PositionalSupplier<T> {
    T getAt(int x, int y, int z);
}
