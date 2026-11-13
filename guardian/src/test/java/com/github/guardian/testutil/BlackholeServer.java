package com.github.guardian.testutil;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 假 Redis：一个行为可控的 TCP 服务端，用来注入网络层故障。
 *
 * <p><b>为什么不用 mock 抛 {@code QueryTimeoutException}</b>：那样只能证明「我 catch 住了这个异常」，
 * 证明不了「Lettuce 在命令超时后抛的到底是哪个类型」。而映射逻辑的依据恰恰是后者 ——
 * 如果客户端抛的东西不是我们以为的那个，catch 就漏了，故障会穿透成 500。
 *
 * <p>所以这里起一个真 socket，让客户端<b>真的连上去、真的等、真的超时</b>。
 * 三类故障各自对应一种服务端行为：
 * <ul>
 *   <li>{@link Mode#SILENT} —— 接受连接但永不回包 &rarr; 客户端命令超时</li>
 *   <li>{@link Mode#CLOSE_AFTER_ACCEPT} —— 接受后立刻关闭 &rarr; 对端重置 / EOF</li>
 *   <li>不启动服务端、指向一个空端口 &rarr; 连接被拒</li>
 * </ul>
 *
 * <p>端口用 {@code 0} 让系统分配，避免和本机真实 Redis（6399）或其它测试撞车。
 */
public final class BlackholeServer implements Closeable {

    public enum Mode {
        /** 接受连接，永不响应，也不关闭。 */
        SILENT,
        /** 接受连接后立刻关闭。 */
        CLOSE_AFTER_ACCEPT
    }

    private final ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    public BlackholeServer(Mode mode) throws IOException {
        this.server = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> acceptLoop(mode), "blackhole-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop(Mode mode) {
        while (running) {
            try {
                Socket socket = server.accept();
                accepted.add(socket);
                if (mode == Mode.CLOSE_AFTER_ACCEPT) {
                    socket.close();
                }
            } catch (IOException e) {
                // server.close() 会让 accept 抛异常，这是正常退出路径
                return;
            }
        }
    }

    /** 本实例监听的端口。 */
    public int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭失败不影响测试结论
        }
        for (Socket socket : accepted) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }
}
