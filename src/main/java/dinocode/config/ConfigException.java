package dinocode.config;

/**
 * 配置错误。消息文案直接面向用户（见 checklist §A），Main 捕获后原样输出并以退出码 1 退出。
 */
public class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
