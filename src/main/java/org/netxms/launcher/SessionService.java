package org.netxms.launcher;


public interface SessionService {
    ServerConnection open(String host, int port) throws SessionException;
}
