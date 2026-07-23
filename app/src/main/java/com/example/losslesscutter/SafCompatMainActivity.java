package com.example.losslesscutter;

import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.Button;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compatibility activity for Android builds that hide /proc/&lt;parent-pid&gt;/fd entries
 * from FFmpeg child processes.
 *
 * <p>The existing editor and export configuration remain in {@link MainActivity}. This
 * subclass replaces only the export button handler. It keeps the fast descriptor path
 * when a child process can access it, and otherwise stages input/output through the app
 * cache before invoking FFprobe/FFmpeg.</p>
 */
public class SafCompatMainActivity extends MainActivity {
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Button exportButton = findViewById(R.id.startExportButton);
        exportButton.setOnClickListener(view -> startExportCompat());
    }

    @Override
    protected void onDestroy() {
        exportExecutor.shutdownNow();
        super.onDestroy();
    }

    private void startExportCompat() {
        try {
            Uri inputUri = getMainField("inputUri");
            Uri outputUri = getMainField("outputUri");
            if (inputUri == null) {
                invokeMain("setStatus", "请先选择输入视频");
                return;
            }
            if (outputUri == null) {
                invokeMain("setStatus", "请先选择输出位置");
                return;
            }

            invokeMain("clearLog");
            invokeMain("captureExportSettingsFromUi");
            if (!Boolean.TRUE.equals(invokeMain("applyManualTimesForExport"))) return;

            File ffmpeg = (File) invokeMain("getNativeExecutable", "ffmpeg");
            if (!ffmpeg.exists()) {
                invokeMain("setStatus", "未找到 FFmpeg 二进制");
                invokeMain("appendLog", "需要 CI 生成并打包：prebuilt/ffmpeg/<abi>/libffmpeg.so");
                return;
            }
            ffmpeg.setExecutable(true, false);

            File ffprobe = (File) invokeMain("getNativeExecutable", "ffprobe");
            if (ffprobe.exists()) ffprobe.setExecutable(true, false);
            else invokeMain("appendLog", "未找到 FFprobe，将使用视频轨优先的保守命令。");

            Button exportButton = findViewById(R.id.startExportButton);
            Button chooseOutputButton = findViewById(R.id.chooseOutputButton);
            exportButton.setEnabled(false);
            chooseOutputButton.setEnabled(false);

            long startMs = getMainLongField("startMs");
            long endMs = getMainLongField("endMs");
            long durationMs = endMs - startMs;
            Object settings = getMainField("exportSettings");
            Object finalSettings = invokeMain("copyExportSettings", settings);
            boolean encode = enumFieldName(finalSettings, "mode").equals("ENCODE");
            invokeMain("setStatus", encode ? "正在编码导出..." : "正在无重编码导出...");

            String inputName = getMainField("inputName");
            String container = (String) getObjectField(finalSettings, "container");
            String outputName = (String) invokeMain(
                    "getDisplayName",
                    outputUri,
                    invokeMain("makeOutputName", inputName, encode ? container : null)
            );

            exportExecutor.execute(() -> runExport(
                    inputUri,
                    outputUri,
                    inputName,
                    outputName,
                    ffmpeg,
                    ffprobe,
                    finalSettings,
                    encode,
                    startMs,
                    durationMs
            ));
        } catch (Exception ex) {
            fail(ex);
        }
    }

    private void runExport(
            Uri inputUri,
            Uri outputUri,
            String inputName,
            String outputName,
            File ffmpeg,
            File ffprobe,
            Object settings,
            boolean encode,
            long startMs,
            long durationMs
    ) {
        CompatPath inputPath = null;
        CompatPath outputPath = null;
        try {
            inputPath = prepareInputPath(inputUri, inputName);
            outputPath = prepareOutputPath(outputUri, outputName);
            postLog("裁剪范围：" + formatClock(startMs) + " → " + formatClock(startMs + durationMs));

            Object selection;
            try {
                selection = ffprobe.exists()
                        ? invokeMain("probeStreams", ffprobe, inputPath.path)
                        : videoOnlySelection();
            } catch (Exception probeError) {
                if (inputPath.temporary) throw probeError;
                postLog("文件描述符输入探测失败，切换缓存兼容模式后重试：" + probeError.getMessage());
                inputPath.close();
                inputPath = prepareTemporaryInput(inputUri, inputName);
                selection = invokeMain("probeStreams", ffprobe, inputPath.path);
            }

            for (String message : selectionMessages(selection)) postLog(message);
            List<Integer> mapIndexes = selectionMapIndexes(selection);

            int code = runCutCommand(
                    ffmpeg,
                    inputPath.path,
                    outputPath.path,
                    outputName,
                    startMs,
                    durationMs,
                    mapIndexes,
                    settings,
                    encode
            );

            if (code != 0 && (!inputPath.temporary || !outputPath.temporary)) {
                postLog("文件描述符导出失败，切换完整缓存兼容模式后重试...");
                if (!inputPath.temporary) {
                    inputPath.close();
                    inputPath = prepareTemporaryInput(inputUri, inputName);
                    if (ffprobe.exists()) {
                        selection = invokeMain("probeStreams", ffprobe, inputPath.path);
                        mapIndexes = selectionMapIndexes(selection);
                    }
                }
                if (!outputPath.temporary) {
                    outputPath.close();
                    outputPath = prepareTemporaryOutput(outputName);
                }
                code = runCutCommand(
                        ffmpeg,
                        inputPath.path,
                        outputPath.path,
                        outputName,
                        startMs,
                        durationMs,
                        mapIndexes,
                        settings,
                        encode
                );
            }

            if (code != 0) {
                if (encode) {
                    invokeMain("failOnUi", "FFmpeg 编码导出失败，退出码：" + code);
                    return;
                }

                postLog("第一次输出失败，尝试只保留主视频轨...");
                List<Integer> videoOnly = new ArrayList<>();
                int videoIndex = ((Number) getObjectField(selection, "videoIndex")).intValue();
                videoOnly.add(videoIndex >= 0 ? videoIndex : -1);
                int fallbackCode = runCutCommand(
                        ffmpeg,
                        inputPath.path,
                        outputPath.path,
                        outputName,
                        startMs,
                        durationMs,
                        videoOnly,
                        settings,
                        false
                );
                if (fallbackCode != 0) {
                    invokeMain("failOnUi", "FFmpeg 执行失败，退出码：" + code + " / 兜底：" + fallbackCode);
                    return;
                }
                postLog("兜底成功：已输出视频轨。");
            }

            if (outputPath.temporary) {
                postLog("正在将临时输出写回所选文件...");
                long copied = copyFileToUri(outputPath.temporaryFile, outputUri);
                postLog("已写回输出文件：" + copied + " bytes");
            }

            postLog("输出文件大小：" + invokeMain("readUriSize", outputUri) + " bytes");
            invokeMain(
                    "successOnUi",
                    encode ? "完成：已编码导出并保存" : "完成：已无重编码裁剪并保存"
            );
        } catch (Exception ex) {
            fail(ex);
        } finally {
            closeQuietly(outputPath);
            closeQuietly(inputPath);
        }
    }

    private int runCutCommand(
            File ffmpeg,
            String inputPath,
            String outputPath,
            String outputName,
            long startMs,
            long durationMs,
            List<Integer> mapIndexes,
            Object settings,
            boolean encode
    ) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) (encode
                ? invokeMain(
                        "buildEncodeCutCommand",
                        ffmpeg,
                        inputPath,
                        outputPath,
                        outputName,
                        startMs,
                        durationMs,
                        mapIndexes,
                        settings
                )
                : invokeMain(
                        "buildCopyCutCommand",
                        ffmpeg,
                        inputPath,
                        outputPath,
                        outputName,
                        startMs,
                        durationMs,
                        mapIndexes
                ));
        postLog("执行命令：\n" + invokeMain("joinCommand", command));
        return ((Number) invokeMain("runProcess", command)).intValue();
    }

    private CompatPath prepareInputPath(Uri uri, String fileName) throws Exception {
        ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IllegalStateException("无法打开输入文件描述符");
        CompatPath direct = CompatPath.direct(descriptor);
        if (isPathAccessibleFromChild(direct.path, false)) {
            postLog("使用文件描述符直读输入：" + direct.path);
            return direct;
        }
        direct.close();
        postLog("FFmpeg 子进程无法访问输入文件描述符，改用应用缓存文件。");
        return prepareTemporaryInput(uri, fileName);
    }

    private CompatPath prepareTemporaryInput(Uri uri, String fileName) throws Exception {
        File temporary = createTemporaryFile("input-", fileName);
        try {
            InputStream rawInput = getContentResolver().openInputStream(uri);
            if (rawInput == null) throw new IllegalStateException("无法读取输入文件");
            try (InputStream input = new BufferedInputStream(rawInput);
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                copyStream(input, output);
            }
            postLog("缓存输入文件：" + temporary.getAbsolutePath() + "（" + temporary.length() + " bytes）");
            return CompatPath.temporary(temporary);
        } catch (Exception ex) {
            deleteQuietly(temporary);
            throw ex;
        }
    }

    private CompatPath prepareOutputPath(Uri uri, String fileName) throws Exception {
        ParcelFileDescriptor descriptor;
        try {
            descriptor = getContentResolver().openFileDescriptor(uri, "rwt");
        } catch (Exception ex) {
            postLog("rwt 输出不可用，回退到 w 模式：" + ex.getMessage());
            descriptor = getContentResolver().openFileDescriptor(uri, "w");
        }
        if (descriptor == null) throw new IllegalStateException("无法打开输出文件描述符");

        CompatPath direct = CompatPath.direct(descriptor);
        if (isPathAccessibleFromChild(direct.path, true)) {
            postLog("使用文件描述符直写输出：" + direct.path);
            return direct;
        }
        direct.close();
        postLog("FFmpeg 子进程无法访问输出文件描述符，先写入应用缓存再回写。");
        return prepareTemporaryOutput(fileName);
    }

    private CompatPath prepareTemporaryOutput(String fileName) throws Exception {
        File temporary = createTemporaryFile("output-", fileName);
        postLog("临时输出文件：" + temporary.getAbsolutePath());
        return CompatPath.temporary(temporary);
    }

    private boolean isPathAccessibleFromChild(String path, boolean writable) {
        File shell = new File("/system/bin/sh");
        if (!shell.exists()) return false;
        String test = writable ? "[ -w \"$1\" ]" : "[ -r \"$1\" ]";
        try {
            Process process = new ProcessBuilder(
                    shell.getAbsolutePath(),
                    "-c",
                    test,
                    "fd-check",
                    path
            ).redirectErrorStream(true).start();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[256];
                while (input.read(buffer) >= 0) {
                    // Drain output so the process cannot block.
                }
            }
            return process.waitFor() == 0;
        } catch (Exception ex) {
            postLog("文件描述符子进程可见性检查失败，将使用缓存兼容模式：" + ex.getMessage());
            return false;
        }
    }

    private File createTemporaryFile(String prefix, String fileName) throws Exception {
        File directory = new File(getCacheDir(), "ffmpeg-export");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("无法创建 FFmpeg 临时目录");
        }
        return File.createTempFile(prefix, temporarySuffix(fileName), directory);
    }

    private String temporarySuffix(String fileName) {
        String clean = fileName == null ? "" : fileName.trim();
        int dot = clean.lastIndexOf('.');
        if (dot >= 0 && dot < clean.length() - 1) {
            String extension = clean.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.matches("\\.[a-z0-9]{1,10}")) return extension;
        }
        return ".tmp";
    }

    private long copyFileToUri(File source, Uri uri) throws Exception {
        OutputStream rawOutput;
        try {
            rawOutput = getContentResolver().openOutputStream(uri, "rwt");
        } catch (Exception ex) {
            postLog("rwt 回写不可用，回退到 w 模式：" + ex.getMessage());
            rawOutput = getContentResolver().openOutputStream(uri, "w");
        }
        if (rawOutput == null) throw new IllegalStateException("无法打开输出文件进行回写");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(rawOutput)) {
            return copyStream(input, output);
        }
    }

    private long copyStream(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[1024 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            output.write(buffer, 0, count);
            total += count;
        }
        output.flush();
        return total;
    }

    private Object videoOnlySelection() throws Exception {
        Class<?> selectionClass = Class.forName(MainActivity.class.getName() + "$StreamSelection");
        Method fallback = selectionClass.getDeclaredMethod("videoOnlyFallback");
        fallback.setAccessible(true);
        return fallback.invoke(null);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> selectionMapIndexes(Object selection) throws Exception {
        return new ArrayList<>((List<Integer>) getObjectField(selection, "mapIndexes"));
    }

    @SuppressWarnings("unchecked")
    private List<String> selectionMessages(Object selection) throws Exception {
        return new ArrayList<>((List<String>) getObjectField(selection, "messages"));
    }

    private String enumFieldName(Object target, String fieldName) throws Exception {
        Object value = getObjectField(target, fieldName);
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private <T> T getMainField(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(this);
    }

    private long getMainLongField(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(this);
    }

    private Object getObjectField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private Object invokeMain(String name, Object... arguments) throws Exception {
        Method method = findMainMethod(name, arguments.length);
        method.setAccessible(true);
        try {
            return method.invoke(this, arguments);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw ex;
        }
    }

    private Method findMainMethod(String name, int argumentCount) throws NoSuchMethodException {
        for (Method method : MainActivity.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == argumentCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(name + "/" + argumentCount);
    }

    private String formatClock(long milliseconds) {
        if (milliseconds < 0) milliseconds = 0;
        long hours = milliseconds / 3_600_000;
        long minutes = (milliseconds % 3_600_000) / 60_000;
        long seconds = (milliseconds % 60_000) / 1_000;
        long millis = milliseconds % 1_000;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private void postLog(String message) {
        try {
            invokeMain("postLog", message);
        } catch (Exception ignored) {
        }
    }

    private void fail(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        try {
            invokeMain("failOnUi", message);
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(CompatPath path) {
        if (path == null) return;
        try {
            path.close();
        } catch (Exception ignored) {
        }
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static final class CompatPath implements AutoCloseable {
        final ParcelFileDescriptor descriptor;
        final File temporaryFile;
        final String path;
        final boolean temporary;

        private CompatPath(ParcelFileDescriptor descriptor, File temporaryFile, String path, boolean temporary) {
            this.descriptor = descriptor;
            this.temporaryFile = temporaryFile;
            this.path = path;
            this.temporary = temporary;
        }

        static CompatPath direct(ParcelFileDescriptor descriptor) {
            String path = "/proc/" + android.os.Process.myPid() + "/fd/" + descriptor.getFd();
            return new CompatPath(descriptor, null, path, false);
        }

        static CompatPath temporary(File file) {
            return new CompatPath(null, file, file.getAbsolutePath(), true);
        }

        @Override
        public void close() throws Exception {
            if (descriptor != null) descriptor.close();
            if (temporaryFile != null && temporaryFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporaryFile.delete();
            }
        }
    }
}
