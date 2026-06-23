package com.local.audiobookshelfclient;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "abs_client_prefs";
    private static final int SLEEP_TIMER_DEFAULT_MINUTES = 15;
    private static final int SLEEP_TIMER_STEP_MINUTES = 5;
    private static final int SLEEP_TIMER_MAX_MINUTES = 30;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final List<AudioEntry> currentAudioFiles = new ArrayList<>();

    private EditText serverInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private TextView statusText;
    private TextView titleText;
    private TextView nowPlayingText;
    private TextView timeText;
    private Button playPauseButton;
    private Button sleepTimerButton;
    private LinearLayout loginPanel;
    private LinearLayout quickPanel;
    private SeekBar seekBar;
    private LinearLayout contentList;
    private ProgressBar loadingBar;
    private SharedPreferences prefs;

    private MediaPlayer mediaPlayer;
    private boolean mediaPrepared = false;
    private String remoteSessionId = "";
    private long lastRemoteSyncAt = 0;
    private String currentItemId;
    private String currentBookTitle;
    private int currentAudioIndex = -1;
    private boolean userSeeking = false;
    private int pendingResumeMs = -1;
    private String pendingResumeIno = "";
    private int settingsTapCount = 0;
    private long lastSettingsTapAt = 0;
    private int sleepTimerMinutes = 0;
    private long sleepTimerEndsAt = 0;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateSleepTimerUi();
            updateProgressUi();
            progressHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        serverInput.setText(prefs.getString("server", "http://192.168.3.102:13378"));
        usernameInput.setText(prefs.getString("username", ""));
        passwordInput.setText(prefs.getString("password", ""));
        updateLoginVisibility();
        setStatus("准备听故事");
        progressHandler.post(progressRunnable);
    }

    @Override
    protected void onDestroy() {
        progressHandler.removeCallbacks(progressRunnable);
        saveCurrentProgress();
        stopPlayback();
        executor.shutdown();
        super.onDestroy();
    }

    private View buildUi() {
        int padding = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(13, 16, 22));

        titleText = new TextView(this);
        titleText.setText("故事小书架");
        titleText.setTextSize(30);
        titleText.setTextColor(Color.rgb(245, 238, 224));
        titleText.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(titleText, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView subtitle = new TextView(this);
        subtitle.setText("给小朋友听故事");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(172, 180, 190));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(26)));

        loginPanel = new LinearLayout(this);
        loginPanel.setOrientation(LinearLayout.VERTICAL);
        serverInput = input("服务器地址", InputType.TYPE_CLASS_TEXT);
        usernameInput = input("用户名", InputType.TYPE_CLASS_TEXT);
        passwordInput = input("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button loginButton = smallButton("登录", Color.rgb(64, 132, 96));
        loginPanel.addView(serverInput);
        loginPanel.addView(usernameInput);
        loginPanel.addView(passwordInput);
        loginPanel.addView(loginButton, new LinearLayout.LayoutParams(-1, dp(38)));
        root.addView(loginPanel);

        quickPanel = new LinearLayout(this);
        quickPanel.setOrientation(LinearLayout.HORIZONTAL);
        quickPanel.setGravity(Gravity.CENTER_VERTICAL);
        Button librariesButton = smallButton("书架", Color.rgb(73, 132, 168));
        Button continueButton = smallButton("继续听", Color.rgb(218, 128, 44));
        sleepTimerButton = smallButton("定时:关", Color.rgb(88, 109, 146));
        Button toggleLoginButton = smallButton("设置", Color.rgb(115, 104, 158));
        quickPanel.addView(librariesButton, smallButtonParams());
        quickPanel.addView(continueButton, smallButtonParams());
        quickPanel.addView(sleepTimerButton, smallButtonParams());
        quickPanel.addView(toggleLoginButton, smallButtonParams());
        root.addView(quickPanel);

        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setVisibility(View.GONE);
        root.addView(loadingBar, new LinearLayout.LayoutParams(-1, dp(5)));

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.rgb(174, 183, 195));
        statusText.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        nowPlayingText = new TextView(this);
        nowPlayingText.setText("还没有播放故事");
        nowPlayingText.setTextSize(17);
        nowPlayingText.setTextColor(Color.rgb(245, 238, 224));
        nowPlayingText.setPadding(dp(14), dp(12), dp(14), dp(8));
        nowPlayingText.setBackground(rounded(Color.rgb(29, 35, 45), 16));
        root.addView(nowPlayingText, new LinearLayout.LayoutParams(-1, -2));

        seekBar = new SeekBar(this);
        root.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout playerActions = new LinearLayout(this);
        playerActions.setOrientation(LinearLayout.HORIZONTAL);
        Button prevButton = controlButton("≪", Color.rgb(75, 123, 156));
        playPauseButton = controlButton("▶", Color.rgb(64, 132, 96));
        Button nextButton = controlButton("≫", Color.rgb(75, 123, 156));
        playerActions.addView(prevButton, buttonParams());
        playerActions.addView(playPauseButton, buttonParams());
        playerActions.addView(nextButton, buttonParams());
        root.addView(playerActions);

        timeText = new TextView(this);
        timeText.setText("00:00 / 00:00");
        timeText.setTextSize(13);
        timeText.setTextColor(Color.rgb(174, 183, 195));
        timeText.setGravity(Gravity.END);
        root.addView(timeText, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scrollView = new ScrollView(this);
        contentList = new LinearLayout(this);
        contentList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentList);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        loginButton.setOnClickListener(v -> login());
        librariesButton.setOnClickListener(v -> loadLibraries());
        toggleLoginButton.setOnClickListener(v -> guardedToggleSettings());
        continueButton.setOnClickListener(v -> continueLastStory());
        sleepTimerButton.setOnClickListener(v -> advanceSleepTimer());
        prevButton.setOnClickListener(v -> playRelative(-1));
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        nextButton.setOnClickListener(v -> playRelative(1));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer.seekTo(seekBar.getProgress());
                        saveCurrentProgress();
                    } catch (Exception ignored) {}
                }
            }
        });
        return root;
    }

    private EditText input(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setTextSize(16);
        editText.setTextColor(Color.rgb(245, 238, 224));
        editText.setHintTextColor(Color.rgb(136, 146, 160));
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setPadding(dp(10), 0, dp(10), 0);
        editText.setBackground(rounded(Color.rgb(29, 35, 45), 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.setMargins(0, dp(6), 0, 0);
        editText.setLayoutParams(params);
        return editText;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        return button;
    }

    private Button bigButton(String text, int color) {
        Button button = button(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(color, 18));
        return button;
    }

    private Button smallButton(String text, int color) {
        Button button = button(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        button.setBackground(rounded(color, 12));
        return button;
    }

    private Button controlButton(String text, int color) {
        Button button = button(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(28);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(color, 18));
        return button;
    }

    private Button iconButton(String text, int iconRes, int color) {
        Button button = button(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(6));
        button.setBackground(rounded(color, 18));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1);
        params.setMargins(dp(3), dp(5), dp(3), dp(5));
        return params;
    }

    private LinearLayout.LayoutParams smallButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
        params.setMargins(dp(2), dp(3), dp(2), dp(3));
        return params;
    }

    private void login() {
        String server = normalizeServer(serverInput.getText().toString());
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            setStatus("请填写服务器、用户名和密码");
            return;
        }
        showLoading(true);
        setStatus("正在登录...");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);
                JSONObject response = requestJson("POST", server + "/login", body, null);
                String token = findToken(response);
                if (token == null || token.length() == 0) {
                    throw new IOException("登录成功但没有找到 token");
                }
                prefs.edit().putString("server", server).putString("username", username).putString("token", token).apply();
                prefs.edit().putString("password", password).apply();
                runOnMain(() -> { showLoading(false); setStatus("登录成功"); loadLibraries(); });
            } catch (Exception e) {
                runOnMain(() -> { showLoading(false); setStatus("登录失败: " + e.getMessage()); });
            }
        });
    }

    private void loadLibraries() {
        String server = normalizeServer(serverInput.getText().toString());
        String token = prefs.getString("token", "");
        if (server.isEmpty()) { setStatus("请填写服务器地址"); return; }
        if (token.isEmpty()) { setStatus("请先登录"); return; }
        prefs.edit().putString("server", server).apply();
        showLoading(true);
        setStatus("正在读取书库...");
        executor.execute(() -> {
            try {
                JSONObject response = requestJson("GET", server + "/api/libraries", null, token);
                JSONArray libraries = firstArray(response, "libraries", "results");
                runOnMain(() -> showLibraries(server, token, libraries));
            } catch (Exception e) {
                runOnMain(() -> { showLoading(false); setStatus("读取书库失败: " + e.getMessage()); });
            }
        });
    }

    private void showLibraries(String server, String token, JSONArray libraries) {
        showLoading(false);
        updateLoginVisibility();
        contentList.removeAllViews();
        setStatus("书库数量: " + libraries.length());
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;
            String id = library.optString("id");
            String name = library.optString("name", "未命名书库");
            TextView row = row("书架  " + name + "\n" + library.optString("mediaType", ""));
            row.setOnClickListener(v -> loadLibraryItems(server, token, id, name));
            contentList.addView(row);
        }
    }

    private void loadLibraryItems(String server, String token, String libraryId, String libraryName) {
        if (libraryId == null || libraryId.isEmpty()) { setStatus("书库 ID 为空"); return; }
        showLoading(true);
        setStatus("正在读取 " + libraryName + "...");
        executor.execute(() -> {
            try {
                String url = server + "/api/libraries/" + libraryId + "/items?limit=100&sort=media.metadata.title";
                JSONObject response = requestJson("GET", url, null, token);
                JSONArray items = firstArray(response, "results", "items");
                runOnMain(() -> showItems(server, token, libraryName, items));
            } catch (Exception e) {
                runOnMain(() -> { showLoading(false); setStatus("读取书籍失败: " + e.getMessage()); });
            }
        });
    }

    private void showItems(String server, String token, String libraryName, JSONArray items) {
        showLoading(false);
        contentList.removeAllViews();
        setStatus(libraryName + " - " + items.length() + " 本");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            String title = itemTitle(item);
            TextView row = row("故事  " + title);
            row.setOnClickListener(v -> loadItemDetails(server, token, id, title));
            contentList.addView(row);
        }
    }

    private void loadItemDetails(String server, String token, String itemId, String title) {
        loadItemDetails(server, token, itemId, title, -1);
    }

    private void loadItemDetails(String server, String token, String itemId, String title, int resumeMs) {
        showLoading(true);
        pendingResumeMs = resumeMs;
        setStatus("正在读取章节: " + title);
        executor.execute(() -> {
            try {
                JSONObject item = requestJson("GET", server + "/api/items/" + itemId, null, token);
                JSONObject media = item.optJSONObject("media");
                JSONArray audioFiles = media == null ? new JSONArray() : media.optJSONArray("audioFiles");
                if (audioFiles == null) audioFiles = new JSONArray();
                JSONArray finalAudioFiles = audioFiles;
                runOnMain(() -> showAudioFiles(server, token, itemId, title, finalAudioFiles));
            } catch (Exception e) {
                runOnMain(() -> { showLoading(false); setStatus("读取章节失败: " + e.getMessage()); });
            }
        });
    }

    private void showAudioFiles(String server, String token, String itemId, String bookTitle, JSONArray audioFiles) {
        showLoading(false);
        contentList.removeAllViews();
        currentAudioFiles.clear();
        currentItemId = itemId;
        currentBookTitle = bookTitle;
        setStatus(bookTitle + " - 章节数: " + audioFiles.length());
        TextView back = row("< 返回书库");
        back.setOnClickListener(v -> loadLibraries());
        contentList.addView(back);
        for (int i = 0; i < audioFiles.length(); i++) {
            JSONObject audio = audioFiles.optJSONObject(i);
            if (audio == null) continue;
            AudioEntry entry = new AudioEntry();
            entry.itemId = itemId;
            entry.server = server;
            entry.ino = audio.optString("ino");
            entry.title = audioTitle(audio, i + 1);
            entry.durationSeconds = audio.optDouble("duration", 0);
            entry.startMs = (int) Math.round(totalDurationBefore(audioFiles, i) * 1000);
            entry.endMs = entry.startMs + (int) Math.round(entry.durationSeconds * 1000);
            entry.url = server + "/api/items/" + itemId + "/file/" + entry.ino;
            entry.token = token;
            currentAudioFiles.add(entry);
            int index = currentAudioFiles.size() - 1;
            int saved = prefs.getInt(progressKey(itemId, entry.ino), 0);
            String suffix = saved > 0 ? "\n上次听到: " + formatTime(saved) : "";
            TextView row = row("播放  " + entry.title + "\n时长: " + formatDuration(entry.durationSeconds) + suffix);
            row.setOnClickListener(v -> playAt(index, true));
            contentList.addView(row);
        }
        if (pendingResumeMs > 0) {
            int resumeIndex = findAudioIndexForBookTime(pendingResumeMs);
            if (resumeIndex >= 0) {
                AudioEntry entry = currentAudioFiles.get(resumeIndex);
                int localMs = Math.max(0, pendingResumeMs - entry.startMs);
                prefs.edit().putInt(progressKey(entry.itemId, entry.ino), localMs).apply();
                playAt(resumeIndex, true);
            }
            pendingResumeMs = -1;
        } else if (pendingResumeIno.length() > 0) {
            int resumeIndex = findAudioIndexForIno(pendingResumeIno);
            pendingResumeIno = "";
            if (resumeIndex >= 0) playAt(resumeIndex, true);
        }
    }

    private double totalDurationBefore(JSONArray audioFiles, int exclusiveIndex) {
        double total = 0;
        for (int i = 0; i < exclusiveIndex; i++) {
            JSONObject audio = audioFiles.optJSONObject(i);
            if (audio != null) total += audio.optDouble("duration", 0);
        }
        return total;
    }

    private int findAudioIndexForBookTime(int bookTimeMs) {
        for (int i = 0; i < currentAudioFiles.size(); i++) {
            AudioEntry entry = currentAudioFiles.get(i);
            if (bookTimeMs >= entry.startMs && bookTimeMs < entry.endMs) return i;
        }
        return currentAudioFiles.isEmpty() ? -1 : currentAudioFiles.size() - 1;
    }

    private int findAudioIndexForIno(String ino) {
        if (ino == null || ino.length() == 0) return -1;
        for (int i = 0; i < currentAudioFiles.size(); i++) {
            if (ino.equals(currentAudioFiles.get(i).ino)) return i;
        }
        return -1;
    }

    private void playAt(int index, boolean resumeSaved) {
        if (index < 0 || index >= currentAudioFiles.size()) return;
        saveCurrentProgress();
        stopPlaybackOnly();
        currentAudioIndex = index;
        remoteSessionId = "";
        lastRemoteSyncAt = 0;
        AudioEntry entry = currentAudioFiles.get(index);
        nowPlayingText.setText(entry.title);
        prefs.edit()
            .putString("last_item", entry.itemId)
            .putString("last_ino", entry.ino)
            .putString("last_title", entry.title)
            .putLong("last_played_at", System.currentTimeMillis())
            .apply();
        setStatus("正在缓冲: " + entry.title);
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + entry.token);
            player.setDataSource(this, Uri.parse(entry.url), headers);
            player.setOnPreparedListener(mp -> {
                mediaPrepared = true;
                int saved = resumeSaved ? prefs.getInt(progressKey(entry.itemId, entry.ino), 0) : 0;
                if (saved > 0 && saved < mp.getDuration() - 5000) mp.seekTo(saved);
                mp.start();
                seekBar.setMax(mp.getDuration());
                updatePlayPauseButton();
                setStatus("正在播放: " + entry.title);
                startRemoteSession(entry);
            });
            player.setOnCompletionListener(mp -> {
                prefs.edit().remove(progressKey(entry.itemId, entry.ino)).apply();
                closeRemoteSession();
                mediaPrepared = false;
                setStatus("播放完成: " + entry.title);
                updatePlayPauseButton();
                playRelative(1);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                setStatus("播放失败: 播放器还没准备好或音频地址不兼容。错误: " + what + ", " + extra);
                stopPlaybackOnly();
                return true;
            });
            mediaPlayer = player;
            mediaPrepared = false;
            player.prepareAsync();
        } catch (Exception e) {
            setStatus("播放失败: " + e.getMessage());
            stopPlaybackOnly();
        }
    }

    private void playRelative(int delta) {
        if (currentAudioFiles.isEmpty()) {
            setStatus("请先打开一本故事，再用上一集/下一集");
            return;
        }
        int next = currentAudioIndex + delta;
        if (next >= 0 && next < currentAudioFiles.size()) playAt(next, true);
        else setStatus(delta < 0 ? "已经是第一集" : "已经是最后一集");
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (!mediaPrepared) { setStatus("还在缓冲，请稍等一下"); return; }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                saveCurrentProgress();
                updatePlayPauseButton();
                setStatus("已暂停");
            } else {
                mediaPlayer.start();
                updatePlayPauseButton();
                setStatus("继续播放");
            }
        } catch (Exception ignored) {}
    }

    private void seekBy(int deltaMs) {
        if (mediaPlayer == null || !mediaPrepared) return;
        try {
            int target = mediaPlayer.getCurrentPosition() + deltaMs;
            target = Math.max(0, Math.min(target, mediaPlayer.getDuration()));
            mediaPlayer.seekTo(target);
            saveCurrentProgress();
        } catch (Exception ignored) {}
    }

    private void stopPlayback() {
        saveCurrentProgress();
        stopPlaybackOnly();
        currentAudioIndex = -1;
        nowPlayingText.setText("还没有播放故事");
        timeText.setText("00:00 / 00:00");
        seekBar.setProgress(0);
    }

    private void stopPlaybackOnly() {
        closeRemoteSession();
        mediaPrepared = false;
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        updatePlayPauseButton();
    }

    private void advanceSleepTimer() {
        if (sleepTimerMinutes <= 0) {
            sleepTimerMinutes = SLEEP_TIMER_DEFAULT_MINUTES;
        } else if (sleepTimerMinutes < SLEEP_TIMER_MAX_MINUTES) {
            sleepTimerMinutes = Math.min(SLEEP_TIMER_MAX_MINUTES, sleepTimerMinutes + SLEEP_TIMER_STEP_MINUTES);
        } else {
            clearSleepTimer();
            setStatus("定时已关闭");
            return;
        }
        sleepTimerEndsAt = System.currentTimeMillis() + sleepTimerMinutes * 60_000L;
        updateSleepTimerUi();
        setStatus("定时 " + sleepTimerMinutes + " 分钟后停止");
    }

    private void clearSleepTimer() {
        sleepTimerMinutes = 0;
        sleepTimerEndsAt = 0;
        updateSleepTimerButton("定时:关");
    }

    private void updateSleepTimerUi() {
        if (sleepTimerEndsAt <= 0) {
            updateSleepTimerButton("定时:关");
            return;
        }
        long remainingMs = sleepTimerEndsAt - System.currentTimeMillis();
        if (remainingMs <= 0) {
            clearSleepTimer();
            stopPlayback();
            setStatus("定时结束，已停止播放");
            return;
        }
        int remainingMinutes = (int) Math.ceil(remainingMs / 60000.0);
        updateSleepTimerButton("定时:" + remainingMinutes + "分");
    }

    private void updateSleepTimerButton(String text) {
        if (sleepTimerButton != null) sleepTimerButton.setText(text);
    }

    private void updatePlayPauseButton() {
        if (playPauseButton == null) return;
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                playPauseButton.setText("Ⅱ");
                playPauseButton.setBackground(rounded(Color.rgb(218, 128, 44), 18));
            } else {
                playPauseButton.setText("▶");
                playPauseButton.setBackground(rounded(Color.rgb(64, 132, 96), 18));
            }
        } catch (Exception ignored) {
            playPauseButton.setText("▶");
        }
    }

    private void saveCurrentProgress() {
        if (mediaPlayer == null || !mediaPrepared || currentAudioIndex < 0 || currentAudioIndex >= currentAudioFiles.size()) return;
        try {
            int pos = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            AudioEntry entry = currentAudioFiles.get(currentAudioIndex);
            SharedPreferences.Editor editor = prefs.edit();
                editor.putString("last_item", entry.itemId)
                    .putString("last_ino", entry.ino)
                    .putString("last_title", entry.title)
                    .putLong("last_played_at", System.currentTimeMillis());
            if (duration > 0 && pos > 0 && pos < duration - 5000) {
                editor.putInt(progressKey(entry.itemId, entry.ino), pos);
            } else {
                editor.remove(progressKey(entry.itemId, entry.ino));
            }
            editor.apply();
        } catch (Exception ignored) {}
    }

    private void updateProgressUi() {
        if (mediaPlayer == null || !mediaPrepared) return;
        try {
            int pos = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            if (!userSeeking && duration > 0) {
                seekBar.setMax(duration);
                seekBar.setProgress(pos);
            }
            timeText.setText(formatTime(pos) + " / " + formatTime(duration));
            if (mediaPlayer.isPlaying()) {
                saveCurrentProgress();
                syncRemoteProgress(pos, false);
            }
        } catch (Exception ignored) {}
    }

    private void startRemoteSession(AudioEntry entry) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("mediaPlayer", "AudioShelf Lite");
                body.put("forceDirectPlay", true);
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("clientName", "AudioShelf Lite");
                deviceInfo.put("deviceName", "Xiaomi 4");
                deviceInfo.put("deviceId", "android-local-client");
                body.put("deviceInfo", deviceInfo);
                JSONObject response = requestJson("POST", entry.server + "/api/items/" + entry.itemId + "/play", body, entry.token);
                String sessionId = response.optString("id", "");
                runOnMain(() -> {
                    if (currentAudioIndex >= 0 && currentAudioIndex < currentAudioFiles.size() && entry == currentAudioFiles.get(currentAudioIndex)) {
                        remoteSessionId = sessionId;
                        lastRemoteSyncAt = System.currentTimeMillis();
                    }
                });
            } catch (Exception e) {
                runOnMain(() -> setStatus("播放中，本机进度已保存；云端进度暂未同步: " + e.getMessage()));
            }
        });
    }

    private void syncRemoteProgress(int localPositionMs, boolean force) {
        if (remoteSessionId.length() == 0 || currentAudioIndex < 0 || currentAudioIndex >= currentAudioFiles.size()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastRemoteSyncAt < 30000) return;
        AudioEntry entry = currentAudioFiles.get(currentAudioIndex);
        String sessionId = remoteSessionId;
        String token = entry.token;
        String server = entry.server;
        double currentTime = (entry.startMs + localPositionMs) / 1000.0;
        double duration = currentAudioFiles.isEmpty() ? entry.durationSeconds : currentAudioFiles.get(currentAudioFiles.size() - 1).endMs / 1000.0;
        int timeListened = lastRemoteSyncAt == 0 ? 1 : Math.max(1, (int) ((now - lastRemoteSyncAt) / 1000));
        lastRemoteSyncAt = now;
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("currentTime", currentTime);
                body.put("duration", duration);
                body.put("timeListened", timeListened);
                requestJson("POST", server + "/api/session/" + sessionId + "/sync", body, token);
            } catch (Exception ignored) {}
        });
    }

    private void closeRemoteSession() {
        if (remoteSessionId.length() == 0 || mediaPlayer == null || !mediaPrepared || currentAudioIndex < 0 || currentAudioIndex >= currentAudioFiles.size()) return;
        try {
            int localPositionMs = mediaPlayer.getCurrentPosition();
            AudioEntry entry = currentAudioFiles.get(currentAudioIndex);
            String sessionId = remoteSessionId;
            String token = entry.token;
            String server = entry.server;
            double currentTime = (entry.startMs + localPositionMs) / 1000.0;
            double duration = currentAudioFiles.isEmpty() ? entry.durationSeconds : currentAudioFiles.get(currentAudioFiles.size() - 1).endMs / 1000.0;
            int timeListened = lastRemoteSyncAt == 0 ? 1 : Math.max(1, (int) ((System.currentTimeMillis() - lastRemoteSyncAt) / 1000));
            remoteSessionId = "";
            executor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("currentTime", currentTime);
                    body.put("duration", duration);
                    body.put("timeListened", timeListened);
                    requestJson("POST", server + "/api/session/" + sessionId + "/close", body, token);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private JSONObject requestJson(String method, String url, JSONObject body, String token) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream outputStream = connection.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.write(body.toString());
            writer.flush();
            writer.close();
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readAll(stream);
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + text);
        if (text == null || text.trim().isEmpty()) return new JSONObject();
        return new JSONObject(text);
    }

    private String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        reader.close();
        return builder.toString();
    }

    private JSONArray firstArray(JSONObject object, String first, String second) {
        JSONArray array = object.optJSONArray(first);
        if (array == null) array = object.optJSONArray(second);
        return array == null ? new JSONArray() : array;
    }

    private String findToken(JSONObject object) {
        String[] keys = {"token", "accessToken", "access_token", "serverToken"};
        for (String key : keys) {
            String value = object.optString(key, null);
            if (value != null && value.length() > 0) return value;
        }
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            Object value = object.opt(iterator.next());
            if (value instanceof JSONObject) {
                String nested = findToken((JSONObject) value);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private String itemTitle(JSONObject item) {
        String title = item.optString("title");
        JSONObject media = item.optJSONObject("media");
        if ((title == null || title.isEmpty()) && media != null) {
            JSONObject metadata = media.optJSONObject("metadata");
            if (metadata != null) title = metadata.optString("title");
        }
        if (title == null || title.isEmpty()) title = item.optString("relPath", "未命名");
        return title;
    }

    private String audioTitle(JSONObject audio, int fallbackIndex) {
        JSONObject metadata = audio.optJSONObject("metadata");
        String filename = metadata == null ? "" : metadata.optString("filename");
        if (filename == null || filename.isEmpty()) filename = "第 " + fallbackIndex + " 集";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) filename = filename.substring(0, dot);
        return filename;
    }

    private String normalizeServer(String value) {
        String server = value == null ? "" : value.trim();
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        return server;
    }

    private String progressKey(String itemId, String ino) {
        return "progress_" + itemId + "_" + ino;
    }

    private String formatDuration(double seconds) {
        return formatTime((int) Math.max(0, Math.round(seconds * 1000)));
    }

    private String formatTime(int ms) {
        int total = Math.max(0, ms / 1000);
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int seconds = total % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private TextView row(String text) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(17);
        row.setTextColor(Color.rgb(245, 238, 224));
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.setBackground(rounded(Color.rgb(29, 35, 45), 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(params);
        return row;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    private void showLoading(boolean loading) {
        loadingBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void updateLoginVisibility() {
        boolean loggedIn = prefs.getString("token", "").length() > 0;
        loginPanel.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        quickPanel.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
    }

    private void toggleLoginPanel() {
        loginPanel.setVisibility(loginPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void guardedToggleSettings() {
        long now = System.currentTimeMillis();
        if (now - lastSettingsTapAt > 1500) settingsTapCount = 0;
        lastSettingsTapAt = now;
        settingsTapCount++;
        if (settingsTapCount >= 3) {
            settingsTapCount = 0;
            toggleLoginPanel();
            setStatus(loginPanel.getVisibility() == View.VISIBLE ? "设置已打开" : "设置已收起");
        } else {
            setStatus("再连续点 " + (3 - settingsTapCount) + " 次打开设置");
        }
    }

    private void continueLastStory() {
        String server = normalizeServer(serverInput.getText().toString());
        String token = prefs.getString("token", "");
        if (token.length() == 0) {
            setStatus("请先登录");
            return;
        }
        String localItemId = prefs.getString("last_item", "");
        String localIno = prefs.getString("last_ino", "");
        String localTitle = prefs.getString("last_title", "");
        if (localItemId.length() > 0 && localIno.length() > 0) {
            pendingResumeIno = localIno;
            setStatus("继续播放上次听的故事...");
            loadItemDetails(server, token, localItemId, localTitle.length() == 0 ? "继续听" : localTitle);
            return;
        }
        showLoading(true);
        setStatus("正在查找云端上次播放...");
        executor.execute(() -> {
            try {
                JSONObject me = requestJson("GET", server + "/api/me", null, token);
                JSONArray progress = me.optJSONArray("mediaProgress");
                JSONObject best = latestProgress(progress);
                if (best == null) {
                    throw new IOException("还没有可继续的故事");
                }
                String itemId = best.optString("libraryItemId");
                int resumeMs = (int) Math.round(best.optDouble("currentTime", 0) * 1000);
                String title = "继续听";
                runOnMain(() -> { showLoading(false); loadItemDetails(server, token, itemId, title, resumeMs); });
            } catch (Exception e) {
                runOnMain(() -> { showLoading(false); setStatus("继续听失败: " + e.getMessage()); });
            }
        });
    }

    private JSONObject latestProgress(JSONArray progress) {
        if (progress == null) return null;
        JSONObject best = null;
        long bestTime = -1;
        for (int i = 0; i < progress.length(); i++) {
            JSONObject item = progress.optJSONObject(i);
            if (item == null) continue;
            if (item.optBoolean("isFinished", false) || item.optBoolean("hideFromContinueListening", false)) continue;
            if (item.optDouble("currentTime", 0) <= 0) continue;
            long update = item.optLong("lastUpdate", item.optLong("updatedAt", 0));
            if (update >= bestTime) {
                bestTime = update;
                best = item;
            }
        }
        return best;
    }

    private void runOnMain(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class AudioEntry {
        String itemId;
        String server;
        String ino;
        String title;
        String url;
        String token;
        double durationSeconds;
        int startMs;
        int endMs;
    }
}
