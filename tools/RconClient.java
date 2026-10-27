import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 最小 Minecraft RCON 客户端（无第三方依赖，单文件）。
 *
 * 用法：java -cp <编译输出目录> RconClient <host> <port> <password> "<command>"
 * 退出码：0 成功 · 1 参数错误 · 2 连接失败 · 3 认证失败 · 4 协议/IO 错误
 *
 * 协议：包 = [长度:int32LE][请求ID:int32LE][类型:int32LE][载荷][0x00 0x00]
 *       类型 3 = 认证，类型 2 = 执行命令，类型 0 = 响应。
 */
public final class RconClient {

    private static final int TYPE_RESPONSE = 0;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_AUTH = 3;

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("usage: RconClient <host> <port> <password> \"<command>\"");
            System.exit(1);
        }
        String host = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("port is not a number: " + args[1]);
            System.exit(1);
            return;
        }
        String password = args[2];
        // 命令整体作为一个参数传入；多余参数用空格拼回，避免调用方漏加引号。
        StringBuilder cmd = new StringBuilder(args[3]);
        for (int i = 4; i < args.length; i++) {
            cmd.append(' ').append(args[i]);
        }

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 认证：服务器成功时回同 ID，失败回 -1。
            send(out, 1, TYPE_AUTH, password);
            Packet auth = read(in);
            if (auth == null) {
                System.err.println("auth failed: connection closed");
                System.exit(3);
            }
            if (auth.requestId == -1) {
                System.err.println("auth failed: wrong password");
                System.exit(3);
            }

            send(out, 2, TYPE_COMMAND, cmd.toString());
            Packet response = read(in);
            if (response == null) {
                System.err.println("no response: command produced no output");
                System.exit(0);
            }
            System.out.println(response.body);
            System.exit(0);
        } catch (IOException e) {
            System.err.println("rcon connection/io failed: " + e);
            System.exit(2);
        }
    }

    private static void send(OutputStream out, int requestId, int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + payload.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4 + 4 + payload.length + 2); // 长度 = ID + 类型 + 载荷 + 2 个结束符
        buffer.putInt(requestId);
        buffer.putInt(type);
        buffer.put(payload);
        buffer.put((byte) 0).put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    private static Packet read(InputStream in) throws IOException {
        byte[] head = readFully(in, 4);
        if (head == null) {
            return null;
        }
        int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > 1024 * 1024) {
            throw new IOException("unexpected packet length: " + length);
        }
        byte[] frame = readFully(in, length);
        if (frame == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        int requestId = buffer.getInt();
        int type = buffer.getInt();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // 末尾两个 0x00 是结束符，不属于载荷。
        body.write(frame, 8, Math.max(0, length - 8 - 2));
        return new Packet(requestId, type, new String(body.toByteArray(), StandardCharsets.UTF_8));
    }

    private static byte[] readFully(InputStream in, int count) throws IOException {
        byte[] data = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = in.read(data, offset, count - offset);
            if (n < 0) {
                return offset == 0 ? null : throwEof();
            }
            offset += n;
        }
        return data;
    }

    private static byte[] throwEof() throws IOException {
        throw new IOException("connection closed before a full packet was read");
    }

    private static final class Packet {
        final int requestId;
        final int type;
        final String body;

        Packet(int requestId, int type, String body) {
            this.requestId = requestId;
            this.type = type;
            this.body = body;
        }
    }

    private RconClient() {
    }
}
