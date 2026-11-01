package nl.fotolijst.panel;

import android.util.Base64;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import javax.net.ssl.SSLSocketFactory;

/** A small RFC 6455 client: text frames, ping/pong, close. No extensions, no compression. */
final class WebSocketClient {
  interface Listener {
    void onOpen();

    void onText(String text);
  }

  private final Listener listener;
  private final SecureRandom random = new SecureRandom();
  private OutputStream out;
  private Socket socket;
  private volatile boolean closed;

  WebSocketClient(Listener listener) {
    this.listener = listener;
  }

  /** Connects and reads until the connection ends. Returns why it ended. */
  String run(String wsUrl) {
    try {
      URI uri = new URI(wsUrl);
      boolean tls = "wss".equals(uri.getScheme());
      int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
      Socket s = new Socket();
      s.connect(new InetSocketAddress(uri.getHost(), port), 8000);
      if (tls) {
        s = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, uri.getHost(), port, true);
      }
      s.setTcpNoDelay(true);
      s.setSoTimeout(70000);
      socket = s;
      out = s.getOutputStream();
      InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
      handshake(uri, in);
      listener.onOpen();
      readLoop(in);
      return "closed";
    } catch (Exception e) {
      return String.valueOf(e);
    } finally {
      close();
    }
  }

  void close() {
    closed = true;
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
      // already closed
    }
  }

  private void handshake(URI uri, InputStream in) throws IOException {
    byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    String request = "GET " + path + " HTTP/1.1\r\n"
        + "Host: " + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "\r\n"
        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
        + "Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\n"
        + "Sec-WebSocket-Version: 13\r\n\r\n";
    out.write(request.getBytes("UTF-8"));
    out.flush();
    StringBuilder head = new StringBuilder();
    int c;
    while ((c = in.read()) >= 0) {
      head.append((char) c);
      int n = head.length();
      if (n >= 4 && head.charAt(n - 4) == '\r' && head.charAt(n - 3) == '\n'
          && head.charAt(n - 2) == '\r' && head.charAt(n - 1) == '\n') break;
      if (n > 8192) throw new IOException("handshake too long");
    }
    if (!head.toString().startsWith("HTTP/1.1 101")) {
      throw new IOException("handshake refused: " + head.toString().split("\r\n")[0]);
    }
  }

  private void readLoop(InputStream in) throws IOException {
    ByteArrayOutputStream message = new ByteArrayOutputStream();
    while (!closed) {
      int b0 = in.read();
      int b1 = in.read();
      if (b0 < 0 || b1 < 0) return;
      boolean fin = (b0 & 0x80) != 0;
      int op = b0 & 0x0f;
      long len = b1 & 0x7f;
      if (len == 126) {
        len = (in.read() << 8) | in.read();
      } else if (len == 127) {
        len = 0;
        for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
      }
      if (len > 32L * 1024 * 1024) throw new IOException("frame too large");
      byte[] payload = readFully(in, (int) len);
      switch (op) {
        case 0x0:
        case 0x1:
        case 0x2:
          message.write(payload);
          if (fin) {
            String text = message.toString("UTF-8");
            message.reset();
            listener.onText(text);
          }
          break;
        case 0x8:
          return;
        case 0x9:
          writeFrame(0xA, payload);
          break;
        default:
          break;
      }
    }
  }

  private static byte[] readFully(InputStream in, int len) throws IOException {
    byte[] buf = new byte[len];
    int off = 0;
    while (off < len) {
      int n = in.read(buf, off, len - off);
      if (n < 0) throw new IOException("connection closed");
      off += n;
    }
    return buf;
  }

  void sendText(String text) throws IOException {
    writeFrame(0x1, text.getBytes("UTF-8"));
  }

  private synchronized void writeFrame(int op, byte[] payload) throws IOException {
    ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
    frame.write(0x80 | op);
    if (payload.length < 126) {
      frame.write(0x80 | payload.length);
    } else if (payload.length < 65536) {
      frame.write(0x80 | 126);
      frame.write(payload.length >> 8);
      frame.write(payload.length & 0xff);
    } else {
      frame.write(0x80 | 127);
      for (int i = 7; i >= 0; i--) frame.write((int) ((long) payload.length >> (8 * i)) & 0xff);
    }
    byte[] mask = new byte[4];
    random.nextBytes(mask);
    frame.write(mask);
    for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
    out.write(frame.toByteArray());
    out.flush();
  }
}
