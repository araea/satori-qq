package com.satori.qq.update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * 取清单、下载安装包。失败时抛带人话的 {@link IOException}：界面直接把 {@code getMessage()} 给用户看。
 *
 * <p>清单与安装包都很小（几百 KB），不做断点续传；网络抖一下就重试两次。下载边写边算 SHA-256，
 * 与清单里的值不符就删掉文件——代理改写、下载一半都会落在这里。
 */
final class Net {
    private static final int CONNECT_MS = 10_000;
    private static final int READ_MS = 20_000;
    private static final int TRIES = 3;

    private Net() {}

    static String text(String url, int limit, String agent) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            if (attempt > 0) pause(attempt);
            try (Fetch fetch = open(url, agent)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int count;
                while ((count = fetch.stream.read(chunk)) != -1) {
                    out.write(chunk, 0, count);
                    if (out.size() > limit) throw new IOException("服务器返回的内容异常（过大）");
                }
                return out.toString("UTF-8");
            } catch (IOException error) {
                last = explain(error);
                if (fatal(error)) break;
            }
        }
        throw last;
    }

    /** 下载到 {@code dest}（先写 .part，校验通过再改名）。 */
    static void download(String url, File dest, long size, String sha256, String agent) throws IOException {
        IOException last = null;
        File part = new File(dest.getPath() + ".part");
        for (int attempt = 0; attempt < TRIES; attempt++) {
            if (attempt > 0) pause(attempt);
            try (Fetch fetch = open(url, agent)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (FileOutputStream out = new FileOutputStream(part)) {
                    byte[] chunk = new byte[16384];
                    int count;
                    while ((count = fetch.stream.read(chunk)) != -1) {
                        total += count;
                        if (total > size) throw new IOException("下载的文件比预期的大，已放弃");
                        digest.update(chunk, 0, count);
                        out.write(chunk, 0, count);
                    }
                }
                if (total != size) throw new IOException("下载不完整（" + total + " / " + size + " 字节）");
                if (!hex(digest.digest()).equals(sha256)) {
                    throw new IOException("下载的文件校验不通过，可能被网络代理改动过");
                }
                if (dest.exists() && !dest.delete()) throw new IOException("无法覆盖旧的下载文件");
                if (!part.renameTo(dest)) throw new IOException("无法保存下载的文件");
                return;
            } catch (IOException error) {
                part.delete();
                last = explain(error);
                if (fatal(error)) break;
            } catch (NoSuchAlgorithmException error) {
                throw new IOException("系统不支持 SHA-256 校验");
            }
        }
        throw last;
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[16384];
            int count;
            while ((count = in.read(chunk)) != -1) digest.update(chunk, 0, count);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("系统不支持 SHA-256 校验");
        }
    }

    private static final class Fetch implements AutoCloseable {
        final HttpURLConnection connection;
        final InputStream stream;

        Fetch(HttpURLConnection connection) throws IOException {
            this.connection = connection;
            this.stream = connection.getInputStream();
        }

        @Override public void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 已经读完或连接断了，没有可补救的。
            }
            connection.disconnect();
        }
    }

    private static Fetch open(String url, String agent) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_MS);
        connection.setReadTimeout(READ_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", agent);
        connection.setRequestProperty("Accept", "application/octet-stream, application/json, */*");
        int status = connection.getResponseCode();
        if (status / 100 != 2) {
            connection.disconnect();
            throw new HttpStatus(status);
        }
        return new Fetch(connection);
    }

    private static final class HttpStatus extends IOException {
        final int status;

        HttpStatus(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    /** 重试也不会好的错误：地址不存在、被拒绝。 */
    private static boolean fatal(IOException error) {
        return error instanceof HttpStatus && ((HttpStatus) error).status / 100 == 4 && ((HttpStatus) error).status != 429;
    }

    private static IOException explain(IOException error) {
        if (error instanceof HttpStatus) {
            int status = ((HttpStatus) error).status;
            if (status == 404) return new IOException("线上还没有可用的发布文件（404）");
            if (status == 403 || status == 429) return new IOException("GitHub 暂时限制了访问，请稍后再试（" + status + "）");
            return new IOException("GitHub 返回了错误（" + status + "），请稍后再试");
        }
        if (error instanceof UnknownHostException) return new IOException("连不上 GitHub，请检查网络或代理");
        if (error instanceof SocketTimeoutException) return new IOException("连接 GitHub 超时，请检查网络或代理");
        if (error instanceof SSLException) return new IOException("与 GitHub 的安全连接失败，请检查系统时间与网络代理");
        String message = error.getMessage();
        if (message != null && !message.isEmpty() && message.codePoints().anyMatch(c -> c > 0x2E80)) return error;
        return new IOException("网络出错：" + (message == null ? error.getClass().getSimpleName() : message));
    }

    private static void pause(int attempt) throws IOException {
        try {
            Thread.sleep(800L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("已取消");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b));
        return text.toString();
    }
}
