package com.zipgrade.omrscanner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
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

/**
 * Smart Exam Grader v2.0 (Teacher Edition)
 * Designed for everyday teachers and instructors with 3 simple tabs:
 * Tab 1: Scan Paper (Camera, Gallery, Sample Papers, Score Card, Save Student, Use Paper as Answer Key)
 * Tab 2: Visual Bubble Answer Key Editor (Direct circular A / B / C / D buttons for Q1..Q30)
 * Tab 3: Class Gradebook (Saved students, Class Average, Highest Score, 1-Tap Share CSV to WhatsApp/Excel)
 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA_CAPTURE = 1001;
    private static final int REQ_PICK_IMAGE = 1002;
    private static final int REQ_CAMERA_PERM = 1003;
    private static final String PREFS_NAME = "smart_exam_grader_v2";

    private int activeTab = 0; // 0 = Scan, 1 = Answer Key, 2 = Class List
    private int activeQuestionCount = 16;
    private boolean showMistakesOnly = false;
    private boolean hasCustomUserKey = false;
    private String currentPresetHint = "AUTO";
    private String currentStudentName = "الفاتح الصادق (Student 1)";
    private Bitmap currentBitmap = null;
    private OmrNativeCvEngine.GradeOutcome lastOutcome = null;

    private final char[] masterKey = new char[] {
        'A','B','B','C','D','D','D','D','C','C','C','B','B','D','D','A',
        'A','B','C','D','A','B','C','D','A','B','C','D','A','B'
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

    // Top-level Tab Containers
    private Button tabBtnScan;
    private Button tabBtnKey;
    private Button tabBtnClass;

    private ScrollView scanTabContainer;
    private ScrollView keyTabContainer;
    private ScrollView classTabContainer;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSavedState();
        buildTeacherUi();
        loadAssetSample("sheet_16q.jpg", 16, "16Q", "الفاتح الصادق (Form 6353B)");
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
        String savedKey = sp.getString("master_key", null);
        if (savedKey != null && savedKey.length() == 30) {
            for (int i = 0; i < 30; i++) {
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
          .putString("master_key", new String(masterKey))
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
        subtitle.setText("Step 1: Set Answer Key  →  Step 2: Scan Paper  →  Step 3: Save Grade");
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

        // Card A: Camera & Photo Action Buttons
        LinearLayout sourceCard = new LinearLayout(this);
        sourceCard.setOrientation(LinearLayout.VERTICAL);
        sourceCard.setBackground(roundedBox("#1E293B", "#334155", 16));
        sourceCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView sourceLabel = new TextView(this);
        sourceLabel.setText("Scan Student Paper or Pick Photo • تصوير أو اختيار ورقة");
        sourceLabel.setTextColor(Color.WHITE);
        sourceLabel.setTextSize(13f);
        sourceLabel.setTypeface(Typeface.DEFAULT_BOLD);
        sourceCard.addView(sourceLabel);

        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        primaryRow.setPadding(0, dp(8), 0, dp(6));

        Button camBtn = createActionBtn("📷 Scan with Camera", "#10B981", "#020617");
        camBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchCameraCapture();
            }
        });

        Button galBtn = createActionBtn("🖼️ Pick Photo / WhatsApp", "#38BDF8", "#020617");
        galBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchGalleryPicker();
            }
        });

        primaryRow.addView(camBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        View gap = new View(this);
        primaryRow.addView(gap, new LinearLayout.LayoutParams(dp(8), dp(1)));
        primaryRow.addView(galBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        sourceCard.addView(primaryRow);

        // Sample Paper Buttons + Quiz Size Selector
        LinearLayout sampleRow = new LinearLayout(this);
        sampleRow.setOrientation(LinearLayout.HORIZONTAL);

        Button s16Btn = createActionBtn("Try 16-Question Paper", "#0F172A", "#A7F3D0");
        s16Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMasterKeyPreset16Q();
                loadAssetSample("sheet_16q.jpg", 16, "16Q", "الفاتح الصادق (Form 6353B)");
            }
        });

        Button s10Btn = createActionBtn("Try 10-Question Paper", "#0F172A", "#BAE6FD");
        s10Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMasterKeyPreset10Q();
                loadAssetSample("sheet_10q.jpg", 10, "10Q", "الفاتح الصادق (Form 922412)");
            }
        });

        sampleRow.addView(s16Btn, new LinearLayout.LayoutParams(0, dp(38), 1f));
        View gap2 = new View(this);
        sampleRow.addView(gap2, new LinearLayout.LayoutParams(dp(8), dp(1)));
        sampleRow.addView(s10Btn, new LinearLayout.LayoutParams(0, dp(38), 1f));
        sourceCard.addView(sampleRow);

        // Quiz Question Count Bar
        TextView qCountTitle = new TextView(this);
        qCountTitle.setText("Number of Questions in Quiz • عدد أسئلة الاختبار:");
        qCountTitle.setTextColor(Color.parseColor("#94A3B8"));
        qCountTitle.setTextSize(11.5f);
        qCountTitle.setPadding(0, dp(10), 0, dp(4));
        sourceCard.addView(qCountTitle);

        HorizontalScrollView qScroll = new HorizontalScrollView(this);
        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.HORIZONTAL);

        Button autoBtn = createActionBtn("Auto", "#065F46", "#A7F3D0");
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(dp(62), dp(36));
        autoLp.setMargins(0, 0, dp(6), 0);
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentPresetHint = "AUTO";
                hasCustomUserKey = false;
                runOmrPipeline();
                Toast.makeText(MainActivity.this, "Auto-detected " + activeQuestionCount + " Questions", Toast.LENGTH_SHORT).show();
            }
        });
        qRow.addView(autoBtn, autoLp);

        int[] presets = new int[] {10, 15, 16, 20, 25, 30};
        for (final int qVal : presets) {
            Button qb = createActionBtn(qVal + " Q", "#0F172A", "#E2E8F0");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(36));
            lp.setMargins(0, 0, dp(6), 0);
            qb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateQuestionCount(qVal);
                }
            });
            qRow.addView(qb, lp);
        }
        Button minusBtn = createActionBtn("-1", "#334155", "#FFFFFF");
        minusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateQuestionCount(Math.max(1, activeQuestionCount - 1));
            }
        });
        qRow.addView(minusBtn, new LinearLayout.LayoutParams(dp(48), dp(36)));

        View spm = new View(this);
        qRow.addView(spm, new LinearLayout.LayoutParams(dp(6), dp(1)));

        Button plusBtn = createActionBtn("+1", "#334155", "#FFFFFF");
        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateQuestionCount(Math.min(30, activeQuestionCount + 1));
            }
        });
        qRow.addView(plusBtn, new LinearLayout.LayoutParams(dp(48), dp(36)));

        qScroll.addView(qRow);
        sourceCard.addView(qScroll);
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

        // Summary Header Card
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

        // Share CSV & Clear Buttons
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
        activeQuestionCount = newCount;
        currentPresetHint = "CUSTOM";
        if (!hasCustomUserKey) {
            if (newCount == 10) setMasterKeyPreset10Q();
            else if (newCount == 16) setMasterKeyPreset16Q();
        }
        persistState();
        runOmrPipeline();
        Toast.makeText(this, "Quiz size set to " + newCount + " Questions", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(
            this,
            "✓ Saved " + enteredName + " (" + lastOutcome.correctCount + "/" + lastOutcome.totalQuestions + " • " + lastOutcome.scorePercent + "%)",
            Toast.LENGTH_LONG
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

        // If user hasn't manually customized the answer key, auto-sync preset key for detected 10Q vs 16Q forms
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

        lastOutcome = initial;
        activeQuestionCount = lastOutcome.totalQuestions;

        scorePercentText.setText(lastOutcome.scorePercent + "%");
        scoreFractionText.setText("Score: " + lastOutcome.correctCount + " / " + lastOutcome.totalQuestions + " Correct");
        gradeBadgeText.setText("Grade: " + getGradeWord(lastOutcome.scorePercent) + "  •  " + lastOutcome.detectedFormName);
        studentIdText.setText(
            "Student ID: " + lastOutcome.decodedStudentId
            + "   •   Mistakes: " + (lastOutcome.wrongCount + lastOutcome.multipleMarkCount)
        );
        sheetImageView.setImageBitmap(lastOutcome.annotatedBitmap);
        tabBtnKey.setText("2. Key (" + activeQuestionCount + "Q)");
        tabBtnClass.setText("3. Class (" + classList.size() + ")");

        refreshQuestionBreakdownUI();
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

    private void launchCameraCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                return;
            }
        }
        try {
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            startActivityForResult(takePictureIntent, REQ_CAMERA_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, "Camera unavailable; pick a photo from gallery instead.", Toast.LENGTH_LONG).show();
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
