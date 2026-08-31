package dinocode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * MCP 客户端会话（ch07 F6）：JSON-RPC 2.0 三步会话——initialize 握手 → tools/list → tools/call。
 * 轻量自研协议层（MCP 是简单 JSON-RPC 封装；避免引入 Reactor 依赖树），传输由子类承载。
 *
 * <p>线程安全：同一时刻可能多个工具调用并发（只读批），id 配对与请求写串行化。
 */
abstract class CallerSession implements AutoCloseable {

    /** 连接/调用超时（ch07 F9/F10，内置不可配）。 */
    static final long TIMEOUT_SECONDS = 30;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private long nextId = 1;

    /** 单次工具调用（F6 第三步）；返回 tools/call 的完整 result 节点（含 content 与 isError）。 */
    abstract JsonNode callToolNode(String name, Map<String, Object> arguments) throws Exception;

    /** 握手 + 列工具（F6 前两步）；返回工具数组节点。子类按各自传输实现。 */
    abstract JsonNode initializeAndListTools(String clientName, String clientVersion) throws Exception;

    /** 协议交互（握手/列工具）由子类在构造后调用；串行化 id 分配。 */
    synchronized long nextRequestId() {
        return nextId++;
    }

    protected long timeoutMillis() {
        return TIMEOUT_SECONDS * 1000;
    }

    /** 发送请求并等待同 id 响应；由传输子类实现收发细节。 */
    protected abstract JsonNode request(String method, Object params) throws Exception;

    protected void acquireWrite() {
        lock.writeLock().lock();
    }

    protected void releaseWrite() {
        lock.writeLock().unlock();
    }

    @Override
    public void close() {
        // 子类覆盖：stdio 关进程、HTTP 关连接
    }
}
