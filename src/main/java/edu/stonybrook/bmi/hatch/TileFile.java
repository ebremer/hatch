package edu.stonybrook.bmi.hatch;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Positional reads of byte ranges whose offsets and sizes are known, such as stored tiles.
 * Each read fetches exactly the bytes asked for; there is no stream position or read-ahead
 * buffer to refill when tiles are read out of file order.
 */
final class TileFile implements AutoCloseable {
    private final String path;
    private final FileChannel channel;
    private final long length;

    TileFile(String path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(Path.of(path), StandardOpenOption.READ);
        this.length = channel.size();
    }

    long length() {
        return length;
    }

    /** Reads {@code count} bytes at {@code offset}. */
    byte[] read(long offset, int count) throws IOException {
        byte[] b = new byte[count];
        read(offset, b, 0, count);
        return b;
    }

    /** Reads {@code count} bytes at {@code offset} into {@code dst[at]}. */
    void read(long offset, byte[] dst, int at, int count) throws IOException {
        if (offset < 0 || count < 0 || offset > length - count) {
            throw new EOFException(count + " bytes at offset " + offset + " run past the end of "
                + path + " (" + length + " bytes); the file may be truncated");
        }
        ByteBuffer buf = ByteBuffer.wrap(dst, at, count);
        while (buf.hasRemaining()) {
            if (channel.read(buf, offset + (buf.position() - at)) < 0) {
                throw new EOFException("Unexpected end of " + path + " at offset " + (offset + buf.position() - at));
            }
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
