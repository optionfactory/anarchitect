package net.optionfactory.anarchitect.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class CharsetUser {

    public String decode(byte[] bytes) {
        return new String(bytes);
    }

    public String decodeRange(byte[] bytes) {
        return new String(bytes, 0, 1);
    }

    public String decodeWithCharset(byte[] bytes, Charset charset) {
        return new String(bytes, charset);
    }

    public byte[] encode(String s) {
        return s.getBytes();
    }

    public byte[] encodeWithCharset(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public Reader reader(InputStream in) {
        return new InputStreamReader(in);
    }

    public Writer writerWithCharset(OutputStream out) throws IOException {
        return new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }
}
