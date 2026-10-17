package com.portflow.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Database connection settings. Read from -Dportflow.config=path, ./portflow.properties, or defaults. Credentials are never logged. */
public record AppConfig(String url, String user, String password, int poolSize, long poolWaitMs) {

    public static AppConfig load() {
        Properties p = new Properties();
        String explicit = System.getProperty("portflow.config");
        Path file = Path.of(explicit != null ? explicit : "portflow.properties");
        if (Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) { p.load(in); } catch (IOException e) { /* fall back to defaults */ }
        }
        return new AppConfig(
            System.getProperty("portflow.db.url", p.getProperty("db.url", "jdbc:mysql://localhost:3306/portflow?useSSL=false&allowPublicKeyRetrieval=true")),
            System.getProperty("portflow.db.user", p.getProperty("db.user", "root")),
            System.getProperty("portflow.db.password", p.getProperty("db.password", "")),
            Integer.parseInt(p.getProperty("db.pool.size", "12")),
            Long.parseLong(p.getProperty("db.pool.wait.ms", "5000")));
    }

    /** URL with query string and credentials-free host only, safe to show in the UI. */
    public String safeDescription() {
        String u = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        return u + " (user " + user + ")";
    }
}
