package com.clipforge.app;

import android.net.Uri;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class AppState {
    public enum Screen {
        LOGIN, HOME, NEW_PROJECT, PROCESSING, RESULTS, VIEW_CLIP, EDITOR, EXPORT, PROFILE,
        EMAIL_LOGIN, SIGN_UP, RESET_PASSWORD, PROJECTS, PROJECT_DETAILS, ANALYTICS, PRO,
        PERMISSIONS, EXPORT_DONE, ERROR, ACCOUNT, SECURITY_PRIVACY, STORAGE
    }

    public static final class Candidate {
        public final long startMs;
        public final long endMs;
        public final int score;
        public final String title;
        public File localClip;
        public Candidate(long startMs, long endMs, int score, String title) {
            this.startMs = startMs; this.endMs = endMs; this.score = score; this.title = title;
        }
        public long durationMs() { return Math.max(0, endMs - startMs); }
    }

    public Screen screen = Screen.LOGIN;
    public Screen previousScreen = Screen.LOGIN;
    public String userName = "Daniel";
    public String email = "";
    public String password = "";
    public String confirmPassword = "";
    public String authToken = null;
    public Uri selectedVideoUri = null;
    public String selectedVideoName = "Nenhum vídeo selecionado";
    public long selectedVideoDurationMs = 0L;
    public long selectedVideoSizeBytes = 0L;
    public String selectedStyle = "Highlights";
    public String selectedPlatform = "YouTube Shorts";
    public int progress = 0;
    public String progressStage = "Preparando análise";
    public final List<Candidate> candidates = new ArrayList<>();
    public int selectedCandidate = 0;
    public String statusMessage = "";
    public boolean cloudEnabled = false;
    public String cloudRawPath = null;
    public volatile boolean rawUploadComplete = false;
    public String cloudClipPath = null;
    public File lastExportedFile = null;
    public Uri lastExportedUri = null;

    public Candidate currentCandidate() {
        if (candidates.isEmpty()) return null;
        int idx = Math.max(0, Math.min(selectedCandidate, candidates.size() - 1));
        return candidates.get(idx);
    }

    public void navigate(Screen target) {
        previousScreen = screen;
        screen = target;
    }
}
