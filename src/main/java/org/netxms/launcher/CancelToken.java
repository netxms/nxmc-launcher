package org.netxms.launcher;

@FunctionalInterface
public interface CancelToken {
    CancelToken NONE = () -> false;

    boolean cancelled();
}
