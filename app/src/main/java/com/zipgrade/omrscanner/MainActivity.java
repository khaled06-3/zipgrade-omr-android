package com.zipgrade.omrscanner;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart Exam Grader v2.3 Continuous Stream + Multi-Template Edition
 * Features:
 * - Non-Stop Continuous Live Camera Stream Viewfinder (~5.5ms/frame):
 *   Finds the paper, draws live Green/Red/Orange/Blue bubble rings & score right on the live stream,
 *   updates the grade card & breakdown continuously, and auto-saves each new paper WITHOUT stopping the stream!
 * - Manual "📸 Snap & Save" button + "⏸ Pause / ▶ Resume" toggle + "⚡ Auto-Save: ON/OFF" + "🔦 Torch"
 * - Multi-Template Support:
 *   1. Auto-Detect (Default)
 *   2. Form 922412 (10Q + 7-Digit ID)
 *   3. Form 6353B (16Q: 13L + 3R + 7-Digit ID)
 *   4. Form 20Q-ID7 (20Q: 13L + 7R + 7-Digit ID)
 *   5. Standard ZipGrade 20Q (2-Column)
 *   6. Standard ZipGrade 30Q (2-Column)
 *   7. Standard ZipGrade 50Q (3-Column Compact)
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final int REQ_CAMERA_CAPTURE = 1001;
    private static final int REQ_PICK_IMAGE = 1002;
    private static final int REQ_CAMERA_PERM_STREAM = 1003;
    private static final int REQ_CAMERA_PERM_PHOTO = 1004;
    private static final String PREFS_NAME = "smart_exam_grader_v2";

    private int activeTab = 0; // 0 = Scan, 1 = Answer Key, 2 = Class List
    private int activeQuestionCount = 16;
    private boolean showMistakesOnly = false;
    private boolean hasCustomUserKey = false;
    private String currentPresetHint = "AUTO";
    private String selectedTemplateMode = "AUTO";
    private String currentStudentName = "الفاتح الصادق (Student 1)";
    private Bitmap currentBitmap = null;
    private OmrNativeCvEngine.GradeOutcome lastOutcome = null;

    // Continuous Live Camera Stream State
    private boolean isStreamOpen = false;
    private boolean isStreamPaused = false;
    private boolean autoSnapEnabled = true;
    private boolean torchEnabled = false;
    private int consecutiveLockedFrames = 0;
    private int unlockedStreamFrames = 0;
    private String lastAutoSavedSignature = "";
    private long lastLiveUiUpdateMs = 0L;
    private Camera liveCamera = null;
    private int previewW = 0;
    private int previewH = 0;
    private byte[] latestNv21Frame = null;
    private final AtomicBoolean isProcessingStreamFrame = new AtomicBoolean(false);
    private volatile OmrNativeCvEngine.GradeOutcome latestStreamOutcome = null;

    private final char[] masterKey = new char[] {
        'A','B','B','C','D','D','D','D','C','C','C','B','B','D','D','A',
        'A','B','C','D','A','B','C','D','A','B','C','D','A','B',
        'A','B','C','D','A','B','C','D','A','B','C','D','A','B','C','D','A','B','C','D',
        'A','B','C','D','A','B','C','D','A','B','C','D','A','B','C','D','A','B','C','D',
        'A','B','C','D','A','B','C','D','A','B','C','D','A','B','C','D','A','B','C','D',
        'A','B','C','D','A','B','C','D','A','B'
    };

    public static class SavedStudentGrade {
        public final String name;
        public final String studentId;
        public final int correct;
        public final int total;
        public final int percent;
        public final String time;

        public SavedStudentGrade(String name, String studentId, int correct, int total, int percent, String time) {
            this.name = name;
            this.studentId = studentId;
            this.correct = correct;
            this.total = total;
            this.percent = percent;
            this.time = time;
        }
    }

    private final List<SavedStudentGrade> classList = new ArrayList<>();
    private final List<Button> templateButtons = new ArrayList<>();
    private final String[] templateCodes = new String[] {
        "AUTO", "TPL_10Q_ID7", "TPL_16Q_ID7", "TPL_STD_20Q", "TPL_STD_50Q", "TPL_STD_100Q", "TPL_UNIVERSAL_AUTO"
    };

    // Top-level Tab Containers
    private Button tabBtnScan;
    private Button tabBtnKey;
    private Button tabBtnClass;

    private ScrollView scanTabContainer;
    private ScrollView keyTabContainer;
    private ScrollView classTabContainer;

    // Live Stream Views
    private LinearLayout liveStreamCard;
    private SurfaceView cameraSurfaceView;
    private LiveArHudView liveArHudView;
    private TextView streamStatusBanner;
    private Button streamToggleBtn;
    private Button pauseStreamBtn;
    private Button autoSnapToggleBtn;
    private Button torchToggleBtn;

    // Scan Tab Views
    private TextView scorePercentText;
    private TextView scoreFractionText;
    private TextView gradeBadgeText;
    private TextView studentIdText;
    private EditText studentNameInput;
    private ImageView sheetImageView;
    private LinearLayout questionBreakdownContainer;
    private Button filterAllBtn;
    private Button filterMistakesBtn;

    // Key Tab Views
    private LinearLayout keyRowsContainer;
    private TextView keyHeaderSubtitle;

    // Class Tab Views
    private TextView classCountValue;
    private TextView classAvgValue;
    private TextView classHighValue;
    private LinearLayout classRowsContainer;

    /**
     * Custom Real-Time AR Viewfinder Overlay drawn on top of the live Camera SurfaceView.
     * Renders:
     * 1. The 6 detected fiducial squares (TL, TR, ML, MR, BL, BR) & dual-quad homography mesh
     * 2. Real-time graded bubble rings (Green = Correct, Red = Wrong, Orange = Double Mark, Blue = Correct Key)
     * 3. Decoded 7-Digit Student ID rings & live score banner without stopping the stream!
     */
    private class LiveArHudView extends View {
        private final Paint pQuadUpper = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pQuadLower = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pReticleLock = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pReticleSearch = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubbleCorrect = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubbleWrong = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubbleMulti = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubbleKeyHint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubbleId = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pLabelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pLabelTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pScoreSubTxt = new Paint(Paint.ANTI_ALIAS_FLAG);

        public LiveArHudView(Context ctx) {
            super(ctx);
            pQuadUpper.setStyle(Paint.Style.STROKE);
            pQuadUpper.setStrokeWidth(3.0f);
            pQuadUpper.setColor(Color.parseColor("#10B981"));

            pQuadLower.setStyle(Paint.Style.STROKE);
            pQuadLower.setStrokeWidth(3.0f);
            pQuadLower.setColor(Color.parseColor("#38BDF8"));

            pReticleLock.setStyle(Paint.Style.STROKE);
            pReticleLock.setStrokeWidth(4.5f);
            pReticleLock.setColor(Color.parseColor("#10B981"));

            pReticleSearch.setStyle(Paint.Style.STROKE);
            pReticleSearch.setStrokeWidth(3.5f);
            pReticleSearch.setColor(Color.parseColor("#F59E0B"));

            pBubbleCorrect.setStyle(Paint.Style.STROKE);
            pBubbleCorrect.setStrokeWidth(4.0f);
            pBubbleCorrect.setColor(Color.parseColor("#10B981"));

            pBubbleWrong.setStyle(Paint.Style.STROKE);
            pBubbleWrong.setStrokeWidth(4.0f);
            pBubbleWrong.setColor(Color.parseColor("#EF4444"));

            pBubbleMulti.setStyle(Paint.Style.STROKE);
            pBubbleMulti.setStrokeWidth(4.0f);
            pBubbleMulti.setColor(Color.parseColor("#F59E0B"));

            pBubbleKeyHint.setStyle(Paint.Style.STROKE);
            pBubbleKeyHint.setStrokeWidth(3.2f);
            pBubbleKeyHint.setColor(Color.parseColor("#3B82F6"));

            pBubbleId.setStyle(Paint.Style.STROKE);
            pBubbleId.setStrokeWidth(2.8f);
            pBubbleId.setColor(Color.parseColor("#38BDF8"));

            pLabelBg.setStyle(Paint.Style.FILL);
            pLabelBg.setColor(Color.argb(220, 2, 6, 23));

            pLabelTxt.setColor(Color.parseColor("#34D399"));
            pLabelTxt.setTextSize(dp(11));
            pLabelTxt.setTypeface(Typeface.DEFAULT_BOLD);

            pScoreSubTxt.setColor(Color.WHITE);
            pScoreSubTxt.setTextSize(dp(11));
            pScoreSubTxt.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int vw = getWidth();
            int vh = getHeight();
            if (vw <= 0 || vh <= 0) return;

            OmrNativeCvEngine.GradeOutcome out = latestStreamOutcome;
            float sx = (float) vw / OmrNativeCvEngine.CANONICAL_W;
            float sy = (float) vh / OmrNativeCvEngine.CANONICAL_H;

            if (out != null && out.fiducials != null) {
                OmrNativeCvEngine.DetectedFiducials f = out.fiducials;
                boolean locked = out.locked6Fiducials;
                Paint retPaint = locked ? pReticleLock : pReticleSearch;

                if (locked) {
                    Path upper = new Path();
                    upper.moveTo(f.tl.x * sx, f.tl.y * sy);
                    upper.lineTo(f.tr.x * sx, f.tr.y * sy);
                    upper.lineTo(f.mr.x * sx, f.mr.y * sy);
                    upper.lineTo(f.ml.x * sx, f.ml.y * sy);
                    upper.close();
                    canvas.drawPath(upper, pQuadUpper);

                    Path lower = new Path();
                    lower.moveTo(f.ml.x * sx, f.ml.y * sy);
                    lower.lineTo(f.mr.x * sx, f.mr.y * sy);
                    lower.lineTo(f.br.x * sx, f.br.y * sy);
                    lower.lineTo(f.bl.x * sx, f.bl.y * sy);
                    lower.close();
                    canvas.drawPath(lower, pQuadLower);

                    // Draw live graded bubble rings directly on the video stream!
                    char[] opts = new char[] {'A', 'B', 'C', 'D'};
                    float rad = dp(7);
                    for (OmrNativeCvEngine.QuestionResult qr : out.questions) {
                        if (qr.optSrcX == null || qr.optSrcY == null) continue;
                        for (int o = 0; o < 4; o++) {
                            char optChar = opts[o];
                            boolean isMarked = !qr.isBlank && qr.markedOptions.indexOf(optChar) >= 0;
                            float bx = qr.optSrcX[o] * sx;
                            float by = qr.optSrcY[o] * sy;
                            if (isMarked) {
                                Paint bp = qr.isCorrect ? pBubbleCorrect : (qr.isMultipleMark ? pBubbleMulti : pBubbleWrong);
                                canvas.drawCircle(bx, by, rad, bp);
                            } else if (!qr.isCorrect && optChar == qr.correctOption) {
                                canvas.drawCircle(bx, by, rad * 0.85f, pBubbleKeyHint);
                            }
                        }
                    }

                    // Draw live decoded Student ID rings
                    if (out.idSrcX != null && out.idSrcY != null) {
                        for (int i = 0; i < out.idValidCount; i++) {
                            canvas.drawCircle(out.idSrcX[i] * sx, out.idSrcY[i] * sy, dp(5), pBubbleId);
                        }
                    }
                }

                OmrNativeCvEngine.Pt[] pts = new OmrNativeCvEngine.Pt[] {
                    f.tl, f.tr, f.ml, f.mr, f.bl, f.br
                };
                for (OmrNativeCvEngine.Pt pt : pts) {
                    float px = pt.x * sx;
                    float py = pt.y * sy;
                    canvas.drawRect(new RectF(px - dp(11), py - dp(11), px + dp(11), py + dp(11)), retPaint);
                }

                canvas.drawRoundRect(new RectF(dp(8), dp(8), vw - dp(8), dp(54)), dp(10), dp(10), pLabelBg);
                boolean hasSid = out.decodedStudentId != null && !"NO-ID".equals(out.decodedStudentId) && !out.decodedStudentId.contains("?");
                String sidDisp = hasSid ? ("ID: " + out.decodedStudentId) : ("No-ID (Paper #" + (classList.size() + 1) + ")");
                String line1 = locked
                    ? ("✓ PAPER LOCKED (" + out.processingMs + "ms) • " + out.detectedFormName)
                    : ("📹 Live Stream (" + out.processingMs + "ms/frame) • Align 4 or 6 Corner Squares");
                String line2 = locked
                    ? ("Score: " + out.correctCount + "/" + out.totalQuestions + " (" + out.scorePercent + "%)  •  " + sidDisp + (isStreamPaused ? "  [PAUSED]" : "  [LIVE]"))
                    : ("Works with or without Student ID — point at any exam paper to grade live");
                canvas.drawText(line1, dp(15), dp(26), pLabelTxt);
                canvas.drawText(line2, dp(15), dp(45), pScoreSubTxt);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSavedState();
        buildTeacherUi();
        loadAssetSample("sheet_16q.jpg", 16, "16Q", "الفاتح الصادق (Form 6353B)");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLiveCameraStream();
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private GradientDrawable roundedBox(String fillHex, String strokeHex, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setCornerRadius(dp(radiusDp));
        if (strokeHex != null) {
            gd.setStroke(dp(1), Color.parseColor(strokeHex));
        }
        return gd;
    }

    private void loadSavedState() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        activeQuestionCount = sp.getInt("q_count", 16);
        String savedKey = sp.getString("master_key_50", null);
        if (savedKey != null && savedKey.length() <= masterKey.length) {
            for (int i = 0; i < savedKey.length(); i++) {
                masterKey[i] = savedKey.charAt(i);
            }
        }
        String savedGrades = sp.getString("class_grades_v2", "");
        classList.clear();
        if (savedGrades != null && !savedGrades.isEmpty()) {
            String[] lines = savedGrades.split("\n");
            for (String line : lines) {
                String[] parts = line.split("\\|");
                if (parts.length == 6) {
                    try {
                        classList.add(new SavedStudentGrade(
                            parts[0],
                            parts[1],
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]),
                            parts[5]
                        ));
                    } catch (Exception ignored) {}
                }
            }
        }
        if (classList.isEmpty()) {
            classList.add(new SavedStudentGrade("الفاتح الصادق", "4633166", 15, 16, 94, "09:15"));
            classList.add(new SavedStudentGrade("محمد القرني", "4611706", 10, 10, 100, "09:12"));
        }
    }

    private void persistState() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (SavedStudentGrade g : classList) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(g.name.replace("|", " ")).append("|")
              .append(g.studentId).append("|")
              .append(g.correct).append("|")
              .append(g.total).append("|")
              .append(g.percent).append("|")
              .append(g.time);
        }
        sp.edit()
          .putInt("q_count", activeQuestionCount)
          .putString("master_key_50", new String(masterKey))
          .putString("class_grades_v2", sb.toString())
          .apply();
    }

    private void buildTeacherUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));

        // 1. Friendly Teacher Header Bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.parseColor("#020617"));
        header.setPadding(dp(16), dp(14), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("Smart Exam Grader • مصحح الاختبارات");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("v2.3 Continuous Live Stream (~5.5ms OMR) • Non-Stop Grading + 7 Templates");
        subtitle.setTextColor(Color.parseColor("#34D399"));
        subtitle.setTextSize(11.5f);
        subtitle.setPadding(0, dp(3), 0, 0);
        header.addView(subtitle);

        // 3-Tab Navigation Bar
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp(10), 0, 0);

        tabBtnScan = createTabButton("1. Scan Paper", 0);
        tabBtnKey = createTabButton("2. Answer Key", 1);
        tabBtnClass = createTabButton("3. Class List", 2);

        tabRow.addView(tabBtnScan, new LinearLayout.LayoutParams(0, dp(42), 1f));
        View sp1 = new View(this);
        tabRow.addView(sp1, new LinearLayout.LayoutParams(dp(6), dp(1)));
        tabRow.addView(tabBtnKey, new LinearLayout.LayoutParams(0, dp(42), 1f));
        View sp2 = new View(this);
        tabRow.addView(sp2, new LinearLayout.LayoutParams(dp(6), dp(1)));
        tabRow.addView(tabBtnClass, new LinearLayout.LayoutParams(0, dp(42), 1f));

        header.addView(tabRow);
        root.addView(header);

        // 2. Content Frame holding the 3 Tabs
        FrameLayout contentFrame = new FrameLayout(this);
        scanTabContainer = buildScanTab();
        keyTabContainer = buildKeyTab();
        classTabContainer = buildClassTab();

        contentFrame.addView(scanTabContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(keyTabContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(classTabContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(contentFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        switchTab(0);
    }

    private Button createTabButton(String label, final int tabIndex) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(tabIndex);
            }
        });
        return b;
    }

    private void switchTab(int tabIndex) {
        activeTab = tabIndex;
        if (tabIndex != 0 && isStreamOpen) {
            stopLiveCameraStream();
        }
        scanTabContainer.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        keyTabContainer.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        classTabContainer.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);

        styleTabButton(tabBtnScan, tabIndex == 0);
        styleTabButton(tabBtnKey, tabIndex == 1);
        styleTabButton(tabBtnClass, tabIndex == 2);

        if (tabIndex == 1) {
            refreshKeyTabRows();
        } else if (tabIndex == 2) {
            refreshClassTabRows();
        }
    }

    private void styleTabButton(Button b, boolean active) {
        if (active) {
            b.setBackground(roundedBox("#10B981", "#059669", 10));
            b.setTextColor(Color.parseColor("#020617"));
        } else {
            b.setBackground(roundedBox("#1E293B", "#334155", 10));
            b.setTextColor(Color.parseColor("#E2E8F0"));
        }
    }

    private ScrollView buildScanTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));

        // Card A: Camera Stream, Manual Photo, & Gallery Action Buttons
        LinearLayout sourceCard = new LinearLayout(this);
        sourceCard.setOrientation(LinearLayout.VERTICAL);
        sourceCard.setBackground(roundedBox("#1E293B", "#334155", 16));
        sourceCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView sourceLabel = new TextView(this);
        sourceLabel.setText("Continuous Live Stream, Take Photo, or Gallery • بث مستمر أو تصوير");
        sourceLabel.setTextColor(Color.WHITE);
        sourceLabel.setTextSize(13f);
        sourceLabel.setTypeface(Typeface.DEFAULT_BOLD);
        sourceCard.addView(sourceLabel);

        // Streamlined 2-Button Primary Action Row (Fewer Buttons, Cleaner UI)
        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        primaryRow.setPadding(0, dp(6), 0, dp(6));

        streamToggleBtn = createActionBtn("📹 Live Camera Scan", "#10B981", "#020617");
        streamToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isStreamOpen) {
                    stopLiveCameraStream();
                } else {
                    startLiveCameraStreamWithPermCheck();
                }
            }
        });

        Button galBtn = createActionBtn("🖼️ Upload Photo", "#1E293B", "#38BDF8");
        galBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isStreamOpen) stopLiveCameraStream();
                launchGalleryPicker();
            }
        });

        primaryRow.addView(streamToggleBtn, new LinearLayout.LayoutParams(0, dp(46), 1.35f));
        View gap1 = new View(this);
        primaryRow.addView(gap1, new LinearLayout.LayoutParams(dp(8), dp(1)));
        primaryRow.addView(galBtn, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        sourceCard.addView(primaryRow);

        // Embedded Continuous Live Camera Stream Card
        liveStreamCard = new LinearLayout(this);
        liveStreamCard.setOrientation(LinearLayout.VERTICAL);
        liveStreamCard.setBackground(roundedBox("#020617", "#10B981", 14));
        liveStreamCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        liveStreamCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams lscLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lscLp.setMargins(0, dp(6), 0, dp(8));

        streamStatusBanner = new TextView(this);
        streamStatusBanner.setText("📹 Continuous Stream Active • Grades papers non-stop without closing!");
        streamStatusBanner.setTextColor(Color.parseColor("#34D399"));
        streamStatusBanner.setTextSize(11.5f);
        streamStatusBanner.setTypeface(Typeface.DEFAULT_BOLD);
        streamStatusBanner.setPadding(0, 0, 0, dp(6));
        liveStreamCard.addView(streamStatusBanner);

        FrameLayout previewBox = new FrameLayout(this);
        cameraSurfaceView = new SurfaceView(this);
        cameraSurfaceView.getHolder().addCallback(this);
        liveArHudView = new LiveArHudView(this);

        previewBox.addView(cameraSurfaceView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        previewBox.addView(liveArHudView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        liveStreamCard.addView(previewBox);

        // Clean 2-Button Stream Controls (Snap & Save + Pause/Resume)
        LinearLayout streamControlsRow = new LinearLayout(this);
        streamControlsRow.setOrientation(LinearLayout.HORIZONTAL);
        streamControlsRow.setPadding(0, dp(8), 0, 0);

        Button snapNowBtn = createActionBtn("📸 Snap & Save", "#10B981", "#020617");
        snapNowBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                snapshotCurrentStreamFrameKeepStreaming("📸 Saved Student Grade! Point at next paper...");
            }
        });

        pauseStreamBtn = createActionBtn("⏸ Freeze", "#1E293B", "#38BDF8");
        pauseStreamBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isStreamPaused = !isStreamPaused;
                pauseStreamBtn.setText(isStreamPaused ? "▶ Resume" : "⏸ Freeze");
                pauseStreamBtn.setBackground(roundedBox(
                    isStreamPaused ? "#0284C7" : "#1E293B",
                    "#38BDF8",
                    10
                ));
                pauseStreamBtn.setTextColor(isStreamPaused ? Color.WHITE : Color.parseColor("#38BDF8"));
                if (isStreamPaused) {
                    snapshotCurrentStreamFrameKeepStreaming("⏸ Stream Frozen on Current Paper");
                }
                if (liveArHudView != null) liveArHudView.invalidate();
            }
        });

        torchToggleBtn = createActionBtn("🔦", "#1E293B", "#FDE68A");
        torchToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCameraTorch();
            }
        });

        streamControlsRow.addView(snapNowBtn, new LinearLayout.LayoutParams(0, dp(42), 1.4f));
        View sg1 = new View(this);
        streamControlsRow.addView(sg1, new LinearLayout.LayoutParams(dp(6), dp(1)));
        streamControlsRow.addView(pauseStreamBtn, new LinearLayout.LayoutParams(0, dp(42), 0.9f));
        View sg3 = new View(this);
        streamControlsRow.addView(sg3, new LinearLayout.LayoutParams(dp(6), dp(1)));
        streamControlsRow.addView(torchToggleBtn, new LinearLayout.LayoutParams(0, dp(42), 0.45f));

        liveStreamCard.addView(streamControlsRow);
        sourceCard.addView(liveStreamCard, lscLp);

        // Single Unified Exam Size & Template Row (Supports 1 to 100 Questions in 1 Compact Bar)
        TextView tplTitle = new TextView(this);
        tplTitle.setText("Exam Size & Template • عدد الأسئلة (1–100Q):");
        tplTitle.setTextColor(Color.parseColor("#94A3B8"));
        tplTitle.setTextSize(11.5f);
        tplTitle.setPadding(0, dp(6), 0, dp(4));
        sourceCard.addView(tplTitle);

        HorizontalScrollView tplScroll = new HorizontalScrollView(this);
        LinearLayout tplRow = new LinearLayout(this);
        tplRow.setOrientation(LinearLayout.HORIZONTAL);

        String[] tplLabels = new String[] {
            "✨ Auto",
            "10Q",
            "16Q",
            "20Q",
            "50Q",
            "100Q",
            "🌐 Custom Template"
        };
        final int[] tplDefaultQ = new int[] { 0, 10, 16, 20, 50, 100, 0 };

        templateButtons.clear();
        for (int i = 0; i < templateCodes.length; i++) {
            final String code = templateCodes[i];
            final int defQ = tplDefaultQ[i];
            Button tb = createActionBtn(tplLabels[i], i == 0 ? "#065F46" : "#0F172A", i == 0 ? "#A7F3D0" : "#E2E8F0");
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            tlp.setMargins(0, 0, dp(6), 0);
            tb.setPadding(dp(12), 0, dp(12), 0);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("TPL_16Q_ID7".equals(code)) {
                        setMasterKeyPreset16Q();
                        selectTemplateMode(code, 16, false);
                        loadAssetSample("sheet_16q.jpg", 16, "16Q", "الفاتح الصادق (Form 6353B)");
                    } else if ("TPL_10Q_ID7".equals(code)) {
                        setMasterKeyPreset10Q();
                        selectTemplateMode(code, 10, false);
                        loadAssetSample("sheet_10q.jpg", 10, "10Q", "الفاتح الصادق (Form 922412)");
                    } else {
                        selectTemplateMode(code, defQ, true);
                    }
                }
            });
            templateButtons.add(tb);
            tplRow.addView(tb, tlp);
        }

        Button minusBtn = createActionBtn("-1", "#334155", "#FFFFFF");
        minusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateQuestionCount(Math.max(1, activeQuestionCount - 1));
            }
        });
        tplRow.addView(minusBtn, new LinearLayout.LayoutParams(dp(44), dp(36)));

        View spm = new View(this);
        tplRow.addView(spm, new LinearLayout.LayoutParams(dp(5), dp(1)));

        Button plusBtn = createActionBtn("+1", "#334155", "#FFFFFF");
        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateQuestionCount(Math.min(100, activeQuestionCount + 1));
            }
        });
        tplRow.addView(plusBtn, new LinearLayout.LayoutParams(dp(44), dp(36)));

        tplScroll.addView(tplRow);
        sourceCard.addView(tplScroll);
        content.addView(sourceCard);

        // Card B: Celebratory Student Grade Card + Save to Class List
        LinearLayout scoreCard = new LinearLayout(this);
        scoreCard.setOrientation(LinearLayout.VERTICAL);
        scoreCard.setBackground(roundedBox("#064E3B", "#10B981", 18));
        scoreCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.setMargins(0, dp(12), 0, dp(12));

        LinearLayout topScoreRow = new LinearLayout(this);
        topScoreRow.setOrientation(LinearLayout.HORIZONTAL);
        topScoreRow.setGravity(Gravity.CENTER_VERTICAL);

        scorePercentText = new TextView(this);
        scorePercentText.setText("94%");
        scorePercentText.setTextColor(Color.parseColor("#34D399"));
        scorePercentText.setTextSize(30f);
        scorePercentText.setTypeface(Typeface.DEFAULT_BOLD);
        topScoreRow.addView(scorePercentText);

        LinearLayout rightMeta = new LinearLayout(this);
        rightMeta.setOrientation(LinearLayout.VERTICAL);
        rightMeta.setPadding(dp(14), 0, 0, 0);

        scoreFractionText = new TextView(this);
        scoreFractionText.setText("Score: 15 / 16 Correct");
        scoreFractionText.setTextColor(Color.WHITE);
        scoreFractionText.setTextSize(15f);
        scoreFractionText.setTypeface(Typeface.DEFAULT_BOLD);
        rightMeta.addView(scoreFractionText);

        gradeBadgeText = new TextView(this);
        gradeBadgeText.setText("Grade: Excellent • ممتاز");
        gradeBadgeText.setTextColor(Color.parseColor("#A7F3D0"));
        gradeBadgeText.setTextSize(12.5f);
        rightMeta.addView(gradeBadgeText);

        studentIdText = new TextView(this);
        studentIdText.setText("Student ID: 4633166");
        studentIdText.setTextColor(Color.parseColor("#7DD3FC"));
        studentIdText.setTextSize(12f);
        rightMeta.addView(studentIdText);

        topScoreRow.addView(rightMeta);
        scoreCard.addView(topScoreRow);

        // Editable Student Name Input
        TextView nameLabel = new TextView(this);
        nameLabel.setText("Student Name • اسم الطالب:");
        nameLabel.setTextColor(Color.parseColor("#D1FAE5"));
        nameLabel.setTextSize(11.5f);
        nameLabel.setPadding(0, dp(10), 0, dp(4));
        scoreCard.addView(nameLabel);

        studentNameInput = new EditText(this);
        studentNameInput.setText(currentStudentName);
        studentNameInput.setTextColor(Color.WHITE);
        studentNameInput.setHint("Type student name here...");
        studentNameInput.setHintTextColor(Color.parseColor("#94A3B8"));
        studentNameInput.setTextSize(13.5f);
        studentNameInput.setSingleLine(true);
        studentNameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        studentNameInput.setBackground(roundedBox("#022C22", "#10B981", 10));
        studentNameInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        scoreCard.addView(studentNameInput);

        // Save Student & Use Paper as Answer Key Buttons
        LinearLayout gradeActionsRow = new LinearLayout(this);
        gradeActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        gradeActionsRow.setPadding(0, dp(10), 0, 0);

        Button useAsKeyBtn = createActionBtn("🔑 Use as Answer Key", "#022C22", "#FDE68A");
        useAsKeyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCurrentPaperToMasterKey();
            }
        });

        Button saveStudentBtn = createActionBtn("✓ Save Student Grade", "#10B981", "#020617");
        saveStudentBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCurrentStudentToClassList();
            }
        });

        gradeActionsRow.addView(useAsKeyBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        View gap3 = new View(this);
        gradeActionsRow.addView(gap3, new LinearLayout.LayoutParams(dp(8), dp(1)));
        gradeActionsRow.addView(saveStudentBtn, new LinearLayout.LayoutParams(0, dp(44), 1.2f));
        scoreCard.addView(gradeActionsRow);

        content.addView(scoreCard, scLp);

        // Card C: Graded Answer Sheet Preview with Color Legend
        LinearLayout imageCard = new LinearLayout(this);
        imageCard.setOrientation(LinearLayout.VERTICAL);
        imageCard.setBackground(roundedBox("#1E293B", "#334155", 16));
        imageCard.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView legendText = new TextView(this);
        legendText.setText("🟢 Green = Correct   🔴 Red = Wrong   🔵 Blue = Correct Answer   🟠 Orange = Double Mark");
        legendText.setTextColor(Color.parseColor("#CBD5E1"));
        legendText.setTextSize(11f);
        legendText.setGravity(Gravity.CENTER);
        legendText.setPadding(0, 0, 0, dp(8));
        imageCard.addView(legendText);

        sheetImageView = new ImageView(this);
        sheetImageView.setAdjustViewBounds(true);
        sheetImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageCard.addView(sheetImageView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(imageCard);

        // Card D: Question-by-Question Breakdown with Filter (All vs Mistakes Only)
        LinearLayout breakdownHeader = new LinearLayout(this);
        breakdownHeader.setOrientation(LinearLayout.HORIZONTAL);
        breakdownHeader.setGravity(Gravity.CENTER_VERTICAL);
        breakdownHeader.setPadding(0, dp(14), 0, dp(6));

        TextView bdTitle = new TextView(this);
        bdTitle.setText("Question Breakdown • تفاصيل الإجابات");
        bdTitle.setTextColor(Color.WHITE);
        bdTitle.setTextSize(14f);
        bdTitle.setTypeface(Typeface.DEFAULT_BOLD);
        breakdownHeader.addView(bdTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        filterAllBtn = createActionBtn("All", "#10B981", "#020617");
        filterAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMistakesOnly = false;
                refreshQuestionBreakdownUI();
            }
        });
        breakdownHeader.addView(filterAllBtn, new LinearLayout.LayoutParams(dp(56), dp(32)));

        View spf = new View(this);
        breakdownHeader.addView(spf, new LinearLayout.LayoutParams(dp(6), dp(1)));

        filterMistakesBtn = createActionBtn("Mistakes Only", "#1E293B", "#FCA5A5");
        filterMistakesBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMistakesOnly = true;
                refreshQuestionBreakdownUI();
            }
        });
        breakdownHeader.addView(filterMistakesBtn, new LinearLayout.LayoutParams(dp(110), dp(32)));

        content.addView(breakdownHeader);

        questionBreakdownContainer = new LinearLayout(this);
        questionBreakdownContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(questionBreakdownContainer);

        sv.addView(content);
        return sv;
    }

    private void selectTemplateMode(String code, int defaultQ, boolean showToast) {
        selectedTemplateMode = code;
        currentPresetHint = code;
        if (defaultQ > 0) {
            activeQuestionCount = defaultQ;
            if (!hasCustomUserKey) {
                if (defaultQ == 10) setMasterKeyPreset10Q();
                else if (defaultQ == 16) setMasterKeyPreset16Q();
            }
        }
        for (int i = 0; i < templateButtons.size(); i++) {
            boolean active = templateCodes[i].equals(code);
            Button b = templateButtons.get(i);
            b.setBackground(roundedBox(active ? "#065F46" : "#0F172A", active ? "#10B981" : "#475569", 10));
            b.setTextColor(Color.parseColor(active ? "#A7F3D0" : "#E2E8F0"));
        }
        runOmrPipeline();
        if (showToast) {
            Toast.makeText(this, "Template set to " + code + " (" + activeQuestionCount + "Q)", Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // Continuous Non-Stop In-App Live Camera Stream
    // =========================================================================

    private void startLiveCameraStreamWithPermCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA_PERM_STREAM);
                return;
            }
        }
        openLiveCameraStream();
    }

    private void openLiveCameraStream() {
        isStreamOpen = true;
        isStreamPaused = false;
        consecutiveLockedFrames = 0;
        liveStreamCard.setVisibility(View.VISIBLE);
        streamToggleBtn.setText("⏹ Stop Stream");
        streamToggleBtn.setBackground(roundedBox("#EF4444", "#B91C1C", 10));
        streamToggleBtn.setTextColor(Color.WHITE);
        if (pauseStreamBtn != null) {
            pauseStreamBtn.setText("⏸ Freeze");
            pauseStreamBtn.setBackground(roundedBox("#1E293B", "#38BDF8", 10));
            pauseStreamBtn.setTextColor(Color.parseColor("#38BDF8"));
        }
        initCameraIfSurfaceReady();
    }

    private void stopLiveCameraStream() {
        isStreamOpen = false;
        isStreamPaused = false;
        torchEnabled = false;
        releaseCameraHardware();
        if (liveStreamCard != null) {
            liveStreamCard.setVisibility(View.GONE);
        }
        if (streamToggleBtn != null) {
            streamToggleBtn.setText("📹 Live Stream");
            streamToggleBtn.setBackground(roundedBox("#10B981", "#059669", 10));
            streamToggleBtn.setTextColor(Color.parseColor("#020617"));
        }
    }

    private void initCameraIfSurfaceReady() {
        if (!isStreamOpen || liveCamera != null || cameraSurfaceView == null) return;
        SurfaceHolder holder = cameraSurfaceView.getHolder();
        if (holder.getSurface() == null || !holder.getSurface().isValid()) return;

        try {
            liveCamera = Camera.open();
            if (liveCamera == null && Camera.getNumberOfCameras() > 0) {
                liveCamera = Camera.open(0);
            }
            if (liveCamera == null) {
                Toast.makeText(this, "No camera hardware found; opening photo picker.", Toast.LENGTH_LONG).show();
                stopLiveCameraStream();
                return;
            }
            liveCamera.setDisplayOrientation(90);
            Camera.Parameters params = liveCamera.getParameters();
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            Camera.Size bestSize = null;
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            if (sizes != null) {
                int bestDiff = Integer.MAX_VALUE;
                for (Camera.Size s : sizes) {
                    int diff = Math.abs(s.width - 1280) + Math.abs(s.height - 720);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestSize = s;
                    }
                }
            }
            if (bestSize != null) {
                params.setPreviewSize(bestSize.width, bestSize.height);
                previewW = bestSize.width;
                previewH = bestSize.height;
            } else {
                Camera.Size def = params.getPreviewSize();
                previewW = def.width;
                previewH = def.height;
            }
            liveCamera.setParameters(params);
            liveCamera.setPreviewDisplay(holder);
            liveCamera.setPreviewCallback(this);
            liveCamera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "Camera stream error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopLiveCameraStream();
        }
    }

    private void releaseCameraHardware() {
        if (liveCamera != null) {
            try {
                liveCamera.setPreviewCallback(null);
                liveCamera.stopPreview();
                liveCamera.release();
            } catch (Exception ignored) {}
            liveCamera = null;
        }
    }

    private void toggleCameraTorch() {
        if (liveCamera == null) return;
        try {
            Camera.Parameters params = liveCamera.getParameters();
            torchEnabled = !torchEnabled;
            params.setFlashMode(torchEnabled ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            liveCamera.setParameters(params);
            torchToggleBtn.setText(torchEnabled ? "🔦ON" : "🔦");
        } catch (Exception ignored) {}
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        initCameraIfSurfaceReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        initCameraIfSurfaceReady();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCameraHardware();
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera camera) {
        if (!isStreamOpen || isStreamPaused || data == null || previewW <= 0 || previewH <= 0) return;
        latestNv21Frame = data;

        if (!isProcessingStreamFrame.compareAndSet(false, true)) {
            return;
        }

        final int w = previewW;
        final int h = previewH;
        final int qOverride = "AUTO".equals(selectedTemplateMode) ? (hasCustomUserKey ? activeQuestionCount : 0) : activeQuestionCount;
        final String tplMode = selectedTemplateMode;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final OmrNativeCvEngine.GradeOutcome streamOut = OmrNativeCvEngine.analyzeNv21StreamFrame(
                        data,
                        w,
                        h,
                        true,
                        qOverride,
                        masterKey,
                        tplMode
                    );
                    latestStreamOutcome = streamOut;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isStreamOpen) return;
                            if (streamOut.locked6Fiducials) {
                                consecutiveLockedFrames++;
                                unlockedStreamFrames = 0;
                                boolean hasValidId = streamOut.decodedStudentId != null
                                    && !"NO-ID".equals(streamOut.decodedStudentId)
                                    && !streamOut.decodedStudentId.contains("?");
                                String idTag = hasValidId
                                    ? ("ID: " + streamOut.decodedStudentId)
                                    : ("Paper #" + (classList.size() + 1) + " (No-ID)");
                                streamStatusBanner.setText(
                                    "🟢 LIVE GRADED: " + streamOut.correctCount + "/" + streamOut.totalQuestions
                                    + " (" + streamOut.scorePercent + "%) • " + idTag
                                    + " • " + streamOut.processingMs + "ms (Stream continues non-stop!)"
                                );

                                // Continuously update the Live Score Card & Question Breakdown without stopping stream!
                                long now = System.currentTimeMillis();
                                if (now - lastLiveUiUpdateMs >= 280L) {
                                    lastLiveUiUpdateMs = now;
                                    applyOutcomeToScoreUi(streamOut, false);
                                }

                                // On 3rd consecutive locked frame, update annotated preview & auto-save if new paper
                                if (consecutiveLockedFrames == 3) {
                                    Bitmap snapBmp = OmrNativeCvEngine.nv21ToPortraitBitmap(data, w, h, true);
                                    currentBitmap = snapBmp;
                                    OmrNativeCvEngine.GradeOutcome fullOut = OmrNativeCvEngine.gradeSheetBitmap(
                                        snapBmp,
                                        qOverride,
                                        masterKey,
                                        tplMode
                                    );
                                    applyOutcomeToScoreUi(fullOut, true);

                                    if (autoSnapEnabled && !streamOut.answerSignature.equals(lastAutoSavedSignature)) {
                                        lastAutoSavedSignature = streamOut.answerSignature;
                                        int nextPaperNum = classList.size() + 1;
                                        String autoName = hasValidId
                                            ? ("Student #" + streamOut.decodedStudentId)
                                            : ("Paper #" + nextPaperNum + " • ورقة " + nextPaperNum);
                                        studentNameInput.setText(autoName);
                                        saveCurrentStudentToClassList();
                                    }
                                }
                            } else {
                                consecutiveLockedFrames = 0;
                                unlockedStreamFrames++;
                                // If the paper was removed from the camera for >= 3 frames, clear signature so even an identical No-ID paper can be graded next!
                                if (unlockedStreamFrames >= 3) {
                                    lastAutoSavedSignature = "";
                                }
                                streamStatusBanner.setText(
                                    "📹 Continuous Stream (" + streamOut.processingMs + "ms/frame) • Point at next paper to grade live"
                                );
                            }
                            if (liveArHudView != null) {
                                liveArHudView.invalidate();
                            }
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    isProcessingStreamFrame.set(false);
                }
            }
        }).start();
    }

    /**
     * Snapshots and grades the current live camera stream frame WITHOUT stopping the live stream!
     */
    private void snapshotCurrentStreamFrameKeepStreaming(String toastMsg) {
        if (latestNv21Frame != null && previewW > 0 && previewH > 0) {
            Bitmap captured = OmrNativeCvEngine.nv21ToPortraitBitmap(latestNv21Frame, previewW, previewH, true);
            currentBitmap = captured;
            runOmrPipeline();
            saveCurrentStudentToClassList();
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyOutcomeToScoreUi(OmrNativeCvEngine.GradeOutcome outcome, boolean updateImage) {
        lastOutcome = outcome;
        activeQuestionCount = outcome.totalQuestions;
        scorePercentText.setText(outcome.scorePercent + "%");
        scoreFractionText.setText("Score: " + outcome.correctCount + " / " + outcome.totalQuestions + " Correct (" + outcome.processingMs + "ms)");
        gradeBadgeText.setText("Grade: " + getGradeWord(outcome.scorePercent) + "  •  " + outcome.detectedFormName);
        boolean hasValidId = outcome.decodedStudentId != null
            && !"NO-ID".equals(outcome.decodedStudentId)
            && !outcome.decodedStudentId.contains("?");
        String idLabel = hasValidId
            ? ("Student ID: " + outcome.decodedStudentId)
            : ("Student ID: None (Auto Paper #" + (classList.size() + 1) + " • بدون رقم)");
        studentIdText.setText(
            idLabel + "   •   Mistakes: " + (outcome.wrongCount + outcome.multipleMarkCount)
        );
        if (updateImage && outcome.annotatedBitmap != null) {
            sheetImageView.setImageBitmap(outcome.annotatedBitmap);
        }
        tabBtnKey.setText("2. Key (" + activeQuestionCount + "Q)");
        tabBtnClass.setText("3. Class (" + classList.size() + ")");
        refreshQuestionBreakdownUI();
    }

    // =========================================================================
    // Tab 2 (Answer Key) & Tab 3 (Class Gradebook)
    // =========================================================================

    private ScrollView buildKeyTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));

        LinearLayout topCard = new LinearLayout(this);
        topCard.setOrientation(LinearLayout.VERTICAL);
        topCard.setBackground(roundedBox("#1E293B", "#334155", 16));
        topCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("Visual Answer Key Editor • نموذج الإجابة");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topCard.addView(title);

        keyHeaderSubtitle = new TextView(this);
        keyHeaderSubtitle.setText("Tap A, B, C, or D for each question below. Changes save and re-grade immediately.");
        keyHeaderSubtitle.setTextColor(Color.parseColor("#94A3B8"));
        keyHeaderSubtitle.setTextSize(12f);
        keyHeaderSubtitle.setPadding(0, dp(4), 0, dp(10));
        topCard.addView(keyHeaderSubtitle);

        Button copyFromPaperBtn = createActionBtn("✨ Copy Key from Current Scanned Paper", "#065F46", "#A7F3D0");
        copyFromPaperBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCurrentPaperToMasterKey();
            }
        });
        topCard.addView(copyFromPaperBtn, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        content.addView(topCard);

        keyRowsContainer = new LinearLayout(this);
        keyRowsContainer.setOrientation(LinearLayout.VERTICAL);
        keyRowsContainer.setPadding(0, dp(10), 0, 0);
        content.addView(keyRowsContainer);

        sv.addView(content);
        return sv;
    }

    private void refreshKeyTabRows() {
        if (keyRowsContainer == null) return;
        keyRowsContainer.removeAllViews();
        keyHeaderSubtitle.setText("Showing Q1 to Q" + activeQuestionCount + " • Tap any bubble (A / B / C / D) to set the correct answer:");

        char[] opts = new char[] {'A', 'B', 'C', 'D'};
        for (int q = 1; q <= activeQuestionCount; q++) {
            final int qIndex = q - 1;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundedBox("#1E293B", "#334155", 12));
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(6));

            TextView qLabel = new TextView(this);
            qLabel.setText("Question " + q);
            qLabel.setTextColor(Color.WHITE);
            qLabel.setTextSize(13.5f);
            qLabel.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(qLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            for (final char c : opts) {
                boolean selected = (masterKey[qIndex] == c);
                Button b = new Button(this);
                b.setText(String.valueOf(c));
                b.setAllCaps(false);
                b.setTextSize(13f);
                b.setTypeface(Typeface.DEFAULT_BOLD);
                b.setPadding(0, 0, 0, 0);
                if (selected) {
                    b.setBackground(roundedBox("#10B981", "#059669", 22));
                    b.setTextColor(Color.parseColor("#020617"));
                } else {
                    b.setBackground(roundedBox("#0F172A", "#475569", 22));
                    b.setTextColor(Color.parseColor("#CBD5E1"));
                }
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        masterKey[qIndex] = c;
                        hasCustomUserKey = true;
                        persistState();
                        runOmrPipeline();
                        refreshKeyTabRows();
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(42), dp(42));
                blp.setMargins(dp(6), 0, 0, 0);
                row.addView(b, blp);
            }

            keyRowsContainer.addView(row, rlp);
        }
    }

    private ScrollView buildClassTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));

        LinearLayout summaryCard = new LinearLayout(this);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setBackground(roundedBox("#1E293B", "#334155", 16));
        summaryCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("Saved Class Gradebook • سجل درجات الفصل");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        summaryCard.addView(title);

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, dp(10), 0, dp(10));

        classCountValue = new TextView(this);
        classAvgValue = new TextView(this);
        classHighValue = new TextView(this);

        statsRow.addView(buildStatBox("Students", classCountValue, "#FFFFFF"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View s1 = new View(this);
        statsRow.addView(s1, new LinearLayout.LayoutParams(dp(8), dp(1)));
        statsRow.addView(buildStatBox("Class Avg", classAvgValue, "#34D399"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View s2 = new View(this);
        statsRow.addView(s2, new LinearLayout.LayoutParams(dp(8), dp(1)));
        statsRow.addView(buildStatBox("Highest", classHighValue, "#38BDF8"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        summaryCard.addView(statsRow);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button shareBtn = createActionBtn("📤 Share Grades (WhatsApp / CSV)", "#10B981", "#020617");
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareClassGradesCsv();
            }
        });

        Button clearBtn = createActionBtn("Clear List", "#334155", "#FCA5A5");
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                classList.clear();
                persistState();
                refreshClassTabRows();
                Toast.makeText(MainActivity.this, "Cleared saved class list", Toast.LENGTH_SHORT).show();
            }
        });

        actionRow.addView(shareBtn, new LinearLayout.LayoutParams(0, dp(44), 1.6f));
        View gap = new View(this);
        actionRow.addView(gap, new LinearLayout.LayoutParams(dp(8), dp(1)));
        actionRow.addView(clearBtn, new LinearLayout.LayoutParams(0, dp(44), 0.7f));
        summaryCard.addView(actionRow);

        content.addView(summaryCard);

        classRowsContainer = new LinearLayout(this);
        classRowsContainer.setOrientation(LinearLayout.VERTICAL);
        classRowsContainer.setPadding(0, dp(12), 0, 0);
        content.addView(classRowsContainer);

        sv.addView(content);
        return sv;
    }

    private LinearLayout buildStatBox(String label, TextView valueTv, String colorHex) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(roundedBox("#0F172A", "#334155", 12));
        box.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.parseColor("#94A3B8"));
        lbl.setTextSize(11f);
        box.addView(lbl);

        valueTv.setText("0");
        valueTv.setTextColor(Color.parseColor(colorHex));
        valueTv.setTextSize(18f);
        valueTv.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(valueTv);

        return box;
    }

    private void refreshClassTabRows() {
        if (classRowsContainer == null) return;
        classRowsContainer.removeAllViews();

        int count = classList.size();
        int sum = 0;
        int high = 0;
        for (SavedStudentGrade g : classList) {
            sum += g.percent;
            if (g.percent > high) high = g.percent;
        }
        int avg = count > 0 ? Math.round((float) sum / count) : 0;

        classCountValue.setText(String.valueOf(count));
        classAvgValue.setText(avg + "%");
        classHighValue.setText(high + "%");

        if (classList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No students saved yet. Go to '1. Scan Paper' and tap 'Save Student Grade'!");
            empty.setTextColor(Color.parseColor("#94A3B8"));
            empty.setTextSize(13f);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            classRowsContainer.addView(empty);
            return;
        }

        for (SavedStudentGrade item : classList) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(roundedBox("#1E293B", "#334155", 14));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, 0, dp(8));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = new TextView(this);
            nameTv.setText(item.name);
            nameTv.setTextColor(Color.WHITE);
            nameTv.setTextSize(14f);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            left.addView(nameTv);

            TextView subTv = new TextView(this);
            subTv.setText("ID: " + item.studentId + "  •  Time: " + item.time + "  •  " + getGradeWord(item.percent));
            subTv.setTextColor(Color.parseColor("#94A3B8"));
            subTv.setTextSize(11.5f);
            left.addView(subTv);

            card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView scoreTv = new TextView(this);
            scoreTv.setText(item.correct + "/" + item.total + " (" + item.percent + "%)");
            scoreTv.setTextColor(Color.parseColor("#34D399"));
            scoreTv.setTextSize(15f);
            scoreTv.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(scoreTv);

            classRowsContainer.addView(card, clp);
        }
    }

    private Button createActionBtn(String text, String bgHex, String fgHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.parseColor(fgHex));
        b.setBackground(roundedBox(bgHex, "#475569", 10));
        return b;
    }

    private String getGradeWord(int pct) {
        if (pct >= 90) return "Excellent • ممتاز";
        if (pct >= 75) return "Very Good • جيد جداً";
        if (pct >= 60) return "Pass • ناجح";
        return "Needs Review • يحتاج مراجعة";
    }

    private void setMasterKeyPreset16Q() {
        char[] k = new char[] {'A','B','B','C','D','D','D','D','C','C','C','B','B','D','D','A'};
        System.arraycopy(k, 0, masterKey, 0, k.length);
        hasCustomUserKey = false;
        persistState();
    }

    private void setMasterKeyPreset10Q() {
        char[] k = new char[] {'B','D','B','B','C','A','B','D','D','A'};
        System.arraycopy(k, 0, masterKey, 0, k.length);
        hasCustomUserKey = false;
        persistState();
    }

    private void updateQuestionCount(int newCount) {
        activeQuestionCount = Math.max(1, Math.min(100, newCount));
        currentPresetHint = "CUSTOM";
        selectedTemplateMode = activeQuestionCount == 100
            ? "TPL_STD_100Q"
            : (activeQuestionCount == 50 ? "TPL_STD_50Q" : "CUSTOM");
        if (!hasCustomUserKey) {
            if (activeQuestionCount == 10) setMasterKeyPreset10Q();
            else if (activeQuestionCount == 16) setMasterKeyPreset16Q();
        }
        persistState();
        runOmrPipeline();
        Toast.makeText(this, "Quiz size set to " + activeQuestionCount + " Questions", Toast.LENGTH_SHORT).show();
    }

    private void copyCurrentPaperToMasterKey() {
        if (lastOutcome == null) return;
        int copied = 0;
        for (OmrNativeCvEngine.QuestionResult qr : lastOutcome.questions) {
            if (!qr.isBlank && qr.markedOptions.length() > 0) {
                masterKey[qr.questionNumber - 1] = qr.markedOptions.charAt(0);
                copied++;
            }
        }
        hasCustomUserKey = true;
        persistState();
        runOmrPipeline();
        Toast.makeText(this, "✓ Copied " + copied + " answers from this paper into the Answer Key!", Toast.LENGTH_LONG).show();
    }

    private void saveCurrentStudentToClassList() {
        if (lastOutcome == null) return;
        String enteredName = studentNameInput.getText().toString().trim();
        if (enteredName.isEmpty()) {
            enteredName = "Student #" + (classList.size() + 1);
        }
        String timeStr = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        classList.add(0, new SavedStudentGrade(
            enteredName,
            lastOutcome.decodedStudentId,
            lastOutcome.correctCount,
            lastOutcome.totalQuestions,
            lastOutcome.scorePercent,
            timeStr
        ));
        persistState();
        tabBtnClass.setText("3. Class (" + classList.size() + ")");
        Toast.makeText(
            this,
            "✓ Saved " + enteredName + " (" + lastOutcome.correctCount + "/" + lastOutcome.totalQuestions + " • " + lastOutcome.scorePercent + "%)",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void shareClassGradesCsv() {
        StringBuilder csv = new StringBuilder();
        csv.append("Student Name,Student ID,Correct,Total Questions,Score Percent,Grade,Time\n");
        for (SavedStudentGrade g : classList) {
            csv.append("\"").append(g.name).append("\",")
               .append(g.studentId).append(",")
               .append(g.correct).append(",")
               .append(g.total).append(",")
               .append(g.percent).append("%,\"")
               .append(getGradeWord(g.percent)).append("\",")
               .append(g.time).append("\n");
        }
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Exam Grades CSV Report");
        sendIntent.putExtra(Intent.EXTRA_TEXT, csv.toString());
        startActivity(Intent.createChooser(sendIntent, "Share Class Grades (WhatsApp / Email / Excel)"));
    }

    private void loadAssetSample(String assetName, int qCount, String hint, String defaultStudentName) {
        try {
            InputStream is = getAssets().open(assetName);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp != null) {
                currentBitmap = bmp;
                activeQuestionCount = qCount;
                currentPresetHint = hint;
                currentStudentName = defaultStudentName;
                if (studentNameInput != null) {
                    studentNameInput.setText(defaultStudentName);
                }
                runOmrPipeline();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error loading sample: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void runOmrPipeline() {
        if (currentBitmap == null) return;
        int qOverride = ("AUTO".equals(currentPresetHint) || "CAMERA".equals(currentPresetHint) || "GALLERY".equals(currentPresetHint))
            ? 0
            : activeQuestionCount;

        OmrNativeCvEngine.GradeOutcome initial = OmrNativeCvEngine.gradeSheetBitmap(
            currentBitmap,
            qOverride,
            masterKey,
            currentPresetHint
        );

        if (!hasCustomUserKey) {
            if (initial.is10QForm && masterKey[0] != 'B') {
                setMasterKeyPreset10Q();
                initial = OmrNativeCvEngine.gradeSheetBitmap(
                    currentBitmap,
                    qOverride,
                    masterKey,
                    currentPresetHint
                );
            } else if (!initial.is10QForm && initial.totalQuestions == 16 && masterKey[0] != 'A') {
                setMasterKeyPreset16Q();
                initial = OmrNativeCvEngine.gradeSheetBitmap(
                    currentBitmap,
                    qOverride,
                    masterKey,
                    currentPresetHint
                );
            }
        }

        applyOutcomeToScoreUi(initial, true);
    }

    private void refreshQuestionBreakdownUI() {
        if (questionBreakdownContainer == null || lastOutcome == null) return;
        questionBreakdownContainer.removeAllViews();

        styleTabButton(filterAllBtn, !showMistakesOnly);
        styleTabButton(filterMistakesBtn, showMistakesOnly);

        int shown = 0;
        for (OmrNativeCvEngine.QuestionResult qr : lastOutcome.questions) {
            if (showMistakesOnly && qr.isCorrect) continue;
            shown++;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));

            String bg = qr.isCorrect ? "#064E3B" : (qr.isMultipleMark ? "#78350F" : "#7F1D1D");
            String border = qr.isCorrect ? "#10B981" : (qr.isMultipleMark ? "#F59E0B" : "#EF4444");
            row.setBackground(roundedBox(bg, border, 10));

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(6));

            TextView leftTv = new TextView(this);
            leftTv.setText("Q" + qr.questionNumber + "  •  Student Marked: " + qr.markedOptions);
            leftTv.setTextColor(Color.WHITE);
            leftTv.setTextSize(12.5f);
            leftTv.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(leftTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView rightTv = new TextView(this);
            if (qr.isCorrect) {
                rightTv.setText("✓ Correct (" + qr.correctOption + ")");
                rightTv.setTextColor(Color.parseColor("#6EE7B7"));
            } else if (qr.isMultipleMark) {
                rightTv.setText("⚠ Double Mark (Key: " + qr.correctOption + ")");
                rightTv.setTextColor(Color.parseColor("#FDE68A"));
            } else {
                rightTv.setText("✗ Wrong (Key: " + qr.correctOption + ")");
                rightTv.setTextColor(Color.parseColor("#FCA5A5"));
            }
            rightTv.setTextSize(12f);
            rightTv.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(rightTv);

            questionBreakdownContainer.addView(row, rlp);
        }

        if (shown == 0) {
            TextView congrats = new TextView(this);
            congrats.setText("🎉 Perfect Score! Zero mistakes on this paper.");
            congrats.setTextColor(Color.parseColor("#6EE7B7"));
            congrats.setTextSize(13f);
            congrats.setPadding(dp(12), dp(14), dp(12), dp(14));
            questionBreakdownContainer.addView(congrats);
        }
    }

    private void launchSystemCameraCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA_PERM_PHOTO);
                return;
            }
        }
        try {
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            startActivityForResult(takePictureIntent, REQ_CAMERA_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, "System camera unavailable; use Live Stream or Gallery.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchGalleryPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(Intent.createChooser(intent, "Select Exam Answer Sheet Photo"), REQ_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open photo picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQ_CAMERA_PERM_STREAM) {
                openLiveCameraStream();
            } else if (requestCode == REQ_CAMERA_PERM_PHOTO) {
                launchSystemCameraCapture();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        try {
            if (requestCode == REQ_CAMERA_CAPTURE && data.getExtras() != null) {
                Bitmap photo = (Bitmap) data.getExtras().get("data");
                if (photo != null) {
                    currentBitmap = photo;
                    currentPresetHint = "CAMERA";
                    studentNameInput.setText("Scanned Student");
                    runOmrPipeline();
                }
            } else if (requestCode == REQ_PICK_IMAGE && data.getData() != null) {
                Uri uri = data.getData();
                InputStream is = getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                if (bmp != null) {
                    currentBitmap = bmp;
                    currentPresetHint = "GALLERY";
                    studentNameInput.setText("Photo Student");
                    runOmrPipeline();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to process image: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
