package dev.enginehost.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface EngineFileSystem {
    InputStream openRead(String relativePath) throws IOException;
    OutputStream openWrite(String relativePath, boolean append) throws IOException;
    boolean exists(String relativePath);
    String[] list(String relativePath) throws IOException;
}
