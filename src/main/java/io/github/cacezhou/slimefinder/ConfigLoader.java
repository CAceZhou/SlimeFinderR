package io.github.cacezhou.slimefinder;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigLoader {
    private final Properties props = new Properties();

    public ConfigLoader(String fileName) throws IOException {
        try (InputStream input = new FileInputStream(fileName)) {
            props.load(input);
        }
    }

    public long getLong(String key) {
        return Long.parseLong(props.getProperty(key).trim());
    }

    public int getInt(String key) {
        return Integer.parseInt(props.getProperty(key).trim());
    }

    // 获取所有参数并封装
    public SearchParams getSearchParams() {
        return new SearchParams(
                getLong("worldSeed"),
                getInt("centerChunkX"),
                getInt("centerChunkZ"),
                getInt("searchRadius"),
                getInt("slimeRadius"),
                getInt("threadCount"),
                getInt("topN")
        );
    }

    public record SearchParams(
            long seed, int centerX, int centerZ,
            int searchRadius, int slimeRadius,
            int threads, int topN
    ) {}
}
