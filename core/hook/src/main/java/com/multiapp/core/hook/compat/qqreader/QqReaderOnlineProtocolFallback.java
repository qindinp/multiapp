package com.multiapp.core.hook.compat.qqreader;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class QqReaderOnlineProtocolFallback {
    private static final String TAG = "MultiApp-Native";

    private QqReaderOnlineProtocolFallback() {
    }

    public static Object fetch(Object onlineTask, Object tag, String url) {
        if (tag == null || url == null || url.length() == 0) {
            Log.w(TAG, "stub_online_run: protocol fallback skipped: missing tag/url");
            return null;
        }

        ClassLoader guestLoader = onlineTask != null
                ? onlineTask.getClass().getClassLoader()
                : tag.getClass().getClassLoader();
        if (guestLoader == null) {
            guestLoader = tag.getClass().getClassLoader();
        }

        try {
            Class<?> protocolTaskClass = Class.forName(
                    "com.yuewen.component.businesstask.ordinal.ReaderProtocolTask",
                    true,
                    guestLoader
            );
            Class<?> listenerClass = Class.forName(
                    "com.yuewen.component.task.ordinal.qdab",
                    true,
                    guestLoader
            );
            Class<?> readOnlineClass = Class.forName(
                    "com.qq.reader.common.protocol.ReadOnline",
                    true,
                    guestLoader
            );
            Class<?> onlineTagClass = Class.forName(
                    "com.qq.reader.cservice.onlineread.OnlineTag",
                    true,
                    guestLoader
            );

            Constructor<?> ctor = protocolTaskClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object protocolTask = ctor.newInstance();

            boolean urlApplied = setProtocolTaskUrl(protocolTaskClass, protocolTask, url);
            if (!urlApplied) {
                Log.w(TAG, "stub_online_run: protocol fallback cannot apply url to "
                        + protocolTaskClass.getName());
                return null;
            }

            Method readOnlineSearch = readOnlineClass.getDeclaredMethod(
                    "search",
                    InputStream.class,
                    onlineTagClass,
                    String.class
            );
            readOnlineSearch.setAccessible(true);

            AtomicReference<Object> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("search".equals(name) && args != null && args.length >= 3) {
                    Object second = args[1];
                    if (second instanceof InputStream) {
                        try {
                            byte[] response = readAll((InputStream) second);
                            logTarShape(response);
                            Object result = readOnlineSearch.invoke(
                                    null,
                                    new ByteArrayInputStream(response),
                                    tag,
                                    url
                            );
                            resultRef.set(result);
                            Log.w(TAG, "stub_online_run: protocol fallback ReadOnline.search result="
                                    + className(result));
                        } catch (Throwable t) {
                            Throwable real = t.getCause() != null ? t.getCause() : t;
                            errorRef.set(real);
                            Log.w(TAG, "stub_online_run: protocol fallback ReadOnline.search threw "
                                    + real.getClass().getName() + ": " + real.getMessage(), real);
                        }
                    } else if (second instanceof Throwable) {
                        Throwable throwable = (Throwable) second;
                        errorRef.set(throwable);
                        Log.w(TAG, "stub_online_run: protocol fallback listener error "
                                + throwable.getClass().getName() + ": " + throwable.getMessage(), throwable);
                    } else {
                        Log.w(TAG, "stub_online_run: protocol fallback listener search ignored second="
                                + className(second));
                    }
                }
                return defaultValue(method.getReturnType());
            };

            Object listener = Proxy.newProxyInstance(
                    guestLoader,
                    new Class<?>[]{listenerClass},
                    handler
            );

            Method register = protocolTaskClass.getMethod("registerNetTaskListener", listenerClass);
            register.invoke(protocolTask, listener);

            Log.w(TAG, "stub_online_run: protocol fallback ReaderProtocolTask.run url="
                    + getProtocolTaskUrl(protocolTaskClass, protocolTask, url));
            Method run = protocolTaskClass.getMethod("run");
            run.invoke(protocolTask);

            Object result = resultRef.get();
            Throwable error = errorRef.get();
            Log.w(TAG, "stub_online_run: protocol fallback completed result="
                    + className(result)
                    + " error="
                    + (error == null ? "null" : error.getClass().getName() + ": " + error.getMessage()));
            return result;
        } catch (Throwable t) {
            Throwable real = t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "stub_online_run: protocol fallback failed "
                    + real.getClass().getName() + ": " + real.getMessage(), real);
            return null;
        }
    }

    public static void logMiniContentShape(String bid, int cid) {
        if (bid == null || bid.length() == 0 || cid <= 0) {
            Log.w(TAG, "stub_online_run: mini content skipped bid=" + bid + " cid=" + cid);
            return;
        }

        HttpURLConnection connection = null;
        try {
            String url = "https://wxmini.reader.qq.com/api/chapter/content?bid="
                    + bid
                    + "&cid="
                    + cid;
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "QQReader/diagnostic");

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] body = stream == null ? new byte[0] : readAll(stream);
            if (stream != null) {
                stream.close();
            }
            String json = new String(body, StandardCharsets.UTF_8);
            JSONObject object = new JSONObject(json);
            JSONObject data = object.optJSONObject("data");
            int contentLen = 0;
            String dataKeys = "null";
            if (data != null) {
                String content = data.optString("content", "");
                contentLen = content.length();
                dataKeys = keysOf(data).toString();
            }

            Log.w(TAG, "stub_online_run: mini content shape status="
                    + status
                    + " bytes="
                    + body.length
                    + " code="
                    + object.optInt("code", Integer.MIN_VALUE)
                    + " msg="
                    + object.optString("msg", "")
                    + " dataKeys="
                    + dataKeys
                    + " auth="
                    + (data == null ? "null" : data.opt("auth"))
                    + " authType="
                    + (data == null ? "null" : data.opt("authType"))
                    + " fockEncrypt="
                    + (data == null ? "null" : data.opt("fockEncrypt"))
                    + " chapterId="
                    + (data == null ? "null" : data.opt("chapterId"))
                    + " ccid="
                    + (data == null ? "null" : data.opt("ccid"))
                    + " contentLen="
                    + contentLen);
        } catch (Throwable t) {
            Log.w(TAG, "stub_online_run: mini content shape failed "
                    + t.getClass().getName() + ": " + t.getMessage(), t);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static boolean materializeMiniContentEqct(Object tag, String bid, int cid, String expectedEqct) {
        if (bid == null || bid.length() == 0 || cid <= 0 || expectedEqct == null || expectedEqct.length() == 0) {
            Log.w(TAG, "stub_online_run: mini materialize skipped bid=" + bid
                    + " cid=" + cid + " expectedEqct=" + expectedEqct);
            return false;
        }

        HttpURLConnection connection = null;
        try {
            String url = "https://wxmini.reader.qq.com/api/chapter/content?bid="
                    + bid
                    + "&cid="
                    + cid;
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "QQReader/materialize");

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] body = stream == null ? new byte[0] : readAll(stream);
            if (stream != null) {
                stream.close();
            }

            JSONObject object = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONObject data = object.optJSONObject("data");
            String content = data == null ? "" : data.optString("content", "");
            String title = data == null ? "" : data.optString("title", "");
            if (status != 200 || object.optInt("code", -1) != 0 || content.length() == 0) {
                Log.w(TAG, "stub_online_run: mini materialize unusable status=" + status
                        + " code=" + object.optInt("code", -1)
                        + " contentLen=" + content.length());
                return false;
            }

            File dest = new File(expectedEqct);
            File parent = dest.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                Log.w(TAG, "stub_online_run: mini materialize parent unavailable " + parent);
                return false;
            }

            File source = new File(parent, ".mini_" + cid + ".txt");
            String textForParser = withChapterTitle(title, content);
            java.nio.file.Files.write(source.toPath(), textForParser.getBytes(StandardCharsets.UTF_8));

            ClassLoader guestLoader = tag == null ? null : tag.getClass().getClassLoader();
            if (guestLoader == null) {
                guestLoader = Thread.currentThread().getContextClassLoader();
            }
            Class<?> writerClass = Class.forName("com.qq.reader.cservice.onlineread.qdae", true, guestLoader);
            Method writeMethod = writerClass.getDeclaredMethod(
                    "search",
                    String.class,
                    String.class,
                    String.class,
                    Integer.TYPE
            );
            writeMethod.setAccessible(true);
            writeMethod.invoke(null, source.getAbsolutePath(), expectedEqct, bid, cid);

            long destLen = dest.exists() ? dest.length() : -1L;
            Log.w(TAG, "stub_online_run: mini materialize result expectedEqct="
                    + expectedEqct
                    + " size="
                    + destLen
                    + " source="
                    + source.getAbsolutePath()
                    + " titleLen="
                    + title.length()
                    + " contentLen="
                    + content.length());
            return destLen > 0;
        } catch (Throwable t) {
            Throwable real = t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "stub_online_run: mini materialize failed "
                    + real.getClass().getName() + ": " + real.getMessage(), real);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String withChapterTitle(String title, String content) {
        String normalizedTitle = title == null ? "" : title.trim();
        String normalizedContent = content == null ? "" : content;
        if (normalizedTitle.length() == 0) {
            return normalizedContent;
        }
        String contentTrimmed = normalizedContent.trim();
        if (contentTrimmed.startsWith(normalizedTitle)) {
            return normalizedContent;
        }
        return normalizedTitle + "\n\n" + normalizedContent;
    }


    public static void logOnlineDirShape(String baseDir, String expectedEqct, int cid) {
        try {
            File dir = baseDir == null ? null : new File(baseDir);
            File expected = expectedEqct == null ? null : new File(expectedEqct);
            Log.w(TAG, "stub_online_run: java dir shape baseDir=" + baseDir
                    + " dirExists=" + (dir != null && dir.exists())
                    + " dirIsDir=" + (dir != null && dir.isDirectory())
                    + " expected=" + expectedEqct
                    + " expectedExists=" + (expected != null && expected.exists())
                    + " expectedLen=" + (expected != null && expected.exists() ? expected.length() : -1)
                    + " cid=" + cid);

            if (dir == null || !dir.isDirectory()) {
                return;
            }

            File[] files = dir.listFiles();
            if (files == null) {
                Log.w(TAG, "stub_online_run: java dir shape listFiles=null baseDir=" + baseDir);
                return;
            }
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            List<String> items = new ArrayList<>();
            String cidPrefix = cid > 0 ? String.valueOf(cid) : "";
            String cidSuffix = cid > 0 ? "_" + cid + "_s" : "";
            for (File file : files) {
                String name = file.getName();
                boolean interesting = name.endsWith(".eqct")
                        || name.endsWith(".eres")
                        || name.endsWith("_s")
                        || name.equals("chapter.q")
                        || name.equals("book.meta")
                        || name.equals("adv.m")
                        || (!cidPrefix.isEmpty() && name.equals(cidPrefix + ".eqct"))
                        || (!cidPrefix.isEmpty() && name.equals(cidPrefix + ".eres"))
                        || (!cidSuffix.isEmpty() && name.endsWith(cidSuffix));
                if (!interesting) {
                    continue;
                }
                items.add(name + ":exists=" + file.exists()
                        + ",file=" + file.isFile()
                        + ",dir=" + file.isDirectory()
                        + ",len=" + (file.exists() ? file.length() : -1));
                if (items.size() >= 80) {
                    items.add("...");
                    break;
                }
            }
            Log.w(TAG, "stub_online_run: java dir shape files count=" + files.length
                    + " interesting=" + items);
        } catch (Throwable t) {
            Log.w(TAG, "stub_online_run: java dir shape failed "
                    + t.getClass().getName() + ": " + t.getMessage(), t);
        }
    }    private static boolean setProtocolTaskUrl(Class<?> protocolTaskClass, Object protocolTask, String url) {
        try {
            Method setUrl = protocolTaskClass.getMethod("setUrl", String.class);
            setUrl.invoke(protocolTask, url);
            Log.w(TAG, "stub_online_run: protocol fallback url applied via setUrl(String)");
            return true;
        } catch (Throwable t) {
            Throwable real = t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "stub_online_run: protocol fallback setUrl(String) unavailable "
                    + real.getClass().getName() + ": " + real.getMessage());
        }

        try {
            Field urlField = protocolTaskClass.getDeclaredField("mUrl");
            urlField.setAccessible(true);
            urlField.set(protocolTask, url);
            Log.w(TAG, "stub_online_run: protocol fallback url applied via mUrl field");
            return true;
        } catch (Throwable t) {
            Throwable real = t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "stub_online_run: protocol fallback mUrl field unavailable "
                    + real.getClass().getName() + ": " + real.getMessage());
            return false;
        }
    }

    private static String getProtocolTaskUrl(Class<?> protocolTaskClass, Object protocolTask, String fallback) {
        try {
            Method getUrl = protocolTaskClass.getMethod("getUrl");
            Object value = getUrl.invoke(protocolTask);
            return value == null ? "null" : String.valueOf(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == null || type == Void.TYPE || !type.isPrimitive()) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return '\0';
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        return null;
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void logTarShape(byte[] data) {
        if (data == null) {
            Log.w(TAG, "stub_online_run: protocol fallback raw response null");
            return;
        }
        List<String> entries = new ArrayList<>();
        byte[] info = null;
        int offset = 0;
        int guard = 0;
        while (offset + 512 <= data.length && guard++ < 80) {
            boolean empty = true;
            for (int i = 0; i < 512; i++) {
                if (data[offset + i] != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) break;

            String name = readNullTerminatedAscii(data, offset, 100);
            long size = parseTarOctal(data, offset + 124, 12);
            entries.add(name + ":" + size);

            int contentOffset = offset + 512;
            if ("info.txt".equals(name) && size >= 0 && size <= 1024 * 1024
                    && contentOffset + size <= data.length) {
                info = new byte[(int) size];
                System.arraycopy(data, contentOffset, info, 0, (int) size);
            }

            long blocks = (size + 511) / 512;
            offset = contentOffset + (int) (blocks * 512);
        }

        Log.w(TAG, "stub_online_run: protocol fallback tar bytes=" + data.length
                + " entries=" + entries);
        logInfoShape(info);
    }

    private static void logInfoShape(byte[] info) {
        if (info == null) {
            Log.w(TAG, "stub_online_run: protocol fallback info.txt missing");
            return;
        }
        try {
            String json = new String(info, StandardCharsets.UTF_8);
            Object parsed;
            if (json.trim().startsWith("[")) {
                parsed = new JSONArray(json);
            } else {
                parsed = new JSONObject(json);
            }
            if (parsed instanceof JSONArray) {
                JSONArray array = (JSONArray) parsed;
                List<String> items = new ArrayList<>();
                boolean hasBodyUrl = false;
                for (int i = 0; i < array.length() && i < 6; i++) {
                    Object value = array.opt(i);
                    if (value instanceof JSONObject) {
                        JSONObject object = (JSONObject) value;
                        hasBodyUrl |= hasAnyBodyUrl(object);
                        items.add("item" + i
                                + "{keys=" + keysOf(object)
                                + ",safe=" + safeFields(object) + "}");
                    } else {
                        items.add("item" + i + "{" + className(value) + "}");
                    }
                }
                Log.w(TAG, "stub_online_run: protocol fallback info array len="
                        + array.length()
                        + " hasBodyUrl=" + (hasBodyUrl ? 1 : 0)
                        + " " + items);
            } else if (parsed instanceof JSONObject) {
                JSONObject object = (JSONObject) parsed;
                Log.w(TAG, "stub_online_run: protocol fallback info object keys="
                        + keysOf(object)
                        + " hasBodyUrl=" + (hasAnyBodyUrl(object) ? 1 : 0)
                        + " safe=" + safeFields(object));
            }
        } catch (Throwable t) {
            Log.w(TAG, "stub_online_run: protocol fallback info parse failed "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static boolean hasAnyBodyUrl(JSONObject object) {
        return object.has("ctebchaptercosurl")
                || object.has("epubPureUrl")
                || object.has("epubResourceUrl");
    }

    private static List<String> keysOf(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        return keys;
    }

    private static String safeFields(JSONObject object) {
        String[] names = {
                "code",
                "message",
                "free",
                "restype",
                "chapter_id",
                "chapter_uuid",
                "chapter_ccid",
                "encode",
                "paycheckmode",
                "mediaFiles",
                "multiModal",
                "unlockCondition"
        };
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (object.has(name)) {
                out.add(name + "=" + object.opt(name));
            }
        }
        return out.toString();
    }

    private static String readNullTerminatedAscii(byte[] data, int offset, int max) {
        int end = offset;
        int limit = Math.min(data.length, offset + max);
        while (end < limit && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static long parseTarOctal(byte[] data, int offset, int max) {
        long value = 0;
        int limit = Math.min(data.length, offset + max);
        boolean seen = false;
        for (int i = offset; i < limit; i++) {
            int b = data[i] & 0xff;
            if (b == 0 || b == ' ') {
                if (seen) break;
                continue;
            }
            if (b < '0' || b > '7') {
                return -1;
            }
            seen = true;
            value = (value << 3) + (b - '0');
        }
        return seen ? value : 0;
    }
}
