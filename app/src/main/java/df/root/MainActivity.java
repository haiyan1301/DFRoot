package df.root;

import android.app.Activity;
import android.app.LoaderManager;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements IReporter,
        LoaderManager.LoaderCallbacks<MainActivity.ManagerScanResult> {

    private static final String TAG = "dfroot";

    private Button btnRun;
    private ScrollView outputScroll;
    private TextView outputView;
    private Spinner spinnerSuManager;
    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private int mValidSuManagerPos = 0;
    private boolean mRunning;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            outputView.append(msg);
            outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        getActionBar().setSubtitle("@diabl0w github/xda");

        btnRun = findViewById(R.id.btnRun);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);

        showManagerStatus("Scanning SU managers…");
        btnRun.setOnClickListener(v -> {
            mRunning = true;
            btnRun.setEnabled(false);
            outputView.setText("");
            mExec.execute(this::runExploit);
        });

        // Package queries, ELF reads and icon loading must not block the first frame.
        // LoaderManager retains the result across Activity recreation.
        getLoaderManager().initLoader(0, null, this);
    }

    private void showManagerStatus(String message) {
        mValidSuManagerPos = 0;
        spinnerSuManager.setOnItemSelectedListener(null);
        List<SuManagerEntry> entries = new ArrayList<>();
        entries.add(new SuManagerEntry(null, message, null));
        spinnerSuManager.setAdapter(new SuManagerAdapter(this, entries));
        spinnerSuManager.setEnabled(false);
        btnRun.setEnabled(false);
    }

    @Override
    public Loader<ManagerScanResult> onCreateLoader(int id, Bundle args) {
        return new SuManagerLoader(getApplicationContext());
    }

    @Override
    public void onLoadFinished(Loader<ManagerScanResult> loader, ManagerScanResult result) {
        if (result == null || result.error != null) {
            showManagerStatus("Unable to scan SU managers");
            // A failed scan does not mean the saved manager was uninstalled.
            if (result != null) report("SU manager scan failed: " + result.error + "\n");
            return;
        }

        List<SuManagerEntry> entries = new ArrayList<>(result.entries);
        entries.add(0, new SuManagerEntry(null,
                entries.isEmpty() ? "No compatible SU manager found" : "Select a SU Manager", null));

        mValidSuManagerPos = 0;
        spinnerSuManager.setOnItemSelectedListener(null);
        spinnerSuManager.setAdapter(new SuManagerAdapter(this, entries));
        spinnerSuManager.setEnabled(entries.size() > 1);

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
        boolean savedFound = false;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).packageName.equals(saved)) {
                spinnerSuManager.setSelection(i);
                mValidSuManagerPos = i;
                savedFound = true;
                break;
            }
        }
        if (!savedFound && saved != null) { // Saved manager was uninstalled
            prefs.edit().remove(ExploitRunner.PREF_SU_MANAGER).apply();
        }

        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                SuManagerEntry e = entries.get(pos);
                if (e.packageName == null) return;
                mValidSuManagerPos = pos;
                prefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, e.packageName).apply();
                updateRunButton();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateRunButton();
    }

    @Override
    public void onLoaderReset(Loader<ManagerScanResult> loader) {
        showManagerStatus("Scanning SU managers…");
    }

    static final class ManagerScanResult {
        final List<SuManagerEntry> entries;
        final Exception error;

        ManagerScanResult(List<SuManagerEntry> entries, Exception error) {
            this.entries = entries;
            this.error = error;
        }
    }

    private static final class SuManagerLoader extends AsyncTaskLoader<ManagerScanResult> {
        private ManagerScanResult result;

        SuManagerLoader(Context context) {
            super(context);
        }

        @Override
        public ManagerScanResult loadInBackground() {
            try {
                PackageManager pm = getContext().getPackageManager();
                SharedPreferences cachePrefs = getContext().createDeviceProtectedStorageContext()
                        .getSharedPreferences("su_manager_scan_cache", Context.MODE_PRIVATE);
                Map<String, String> saved = new HashMap<>();
                for (Map.Entry<String, ?> entry : cachePrefs.getAll().entrySet()) {
                    if (entry.getValue() instanceof String) {
                        saved.put(entry.getKey(), (String) entry.getValue());
                    }
                }
                SuManagerCache cache = new SuManagerCache(saved);
                Set<String> installed = new HashSet<>();
                List<SuManagerEntry> entries = new ArrayList<>();
                for (PackageInfo pkg : pm.getInstalledPackages(0)) {
                    if (isLoadInBackgroundCanceled()) return null;
                    ApplicationInfo app = pkg.applicationInfo;
                    if (app == null) continue;
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    installed.add(pkg.packageName);
                    if (!cache.hasKsud(pkg.packageName, pkg.lastUpdateTime,
                            app.sourceDir, app.nativeLibraryDir)) continue;
                    entries.add(new SuManagerEntry(app.packageName,
                            pm.getApplicationLabel(app), pm.getApplicationIcon(app)));
                }
                if (isLoadInBackgroundCanceled()) return null;
                cache.retainPackages(installed);
                if (cache.isDirty()) {
                    SharedPreferences.Editor editor = cachePrefs.edit().clear();
                    for (Map.Entry<String, String> entry : cache.snapshot().entrySet()) {
                        editor.putString(entry.getKey(), entry.getValue());
                    }
                    editor.apply();
                }
                entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
                return new ManagerScanResult(entries, null);
            } catch (RuntimeException e) {
                Log.e(TAG, "SU manager scan failed", e);
                return new ManagerScanResult(null, e);
            }
        }

        @Override
        protected void onStartLoading() {
            if (result != null) deliverResult(result);
            // Refresh package metadata after returning from installing/updating a manager.
            // The persistent cache avoids reading unchanged native libraries again.
            forceLoad();
        }

        @Override
        public void deliverResult(ManagerScanResult data) {
            if (isReset()) return;
            result = data;
            if (isStarted()) super.deliverResult(data);
        }

        @Override
        protected void onStopLoading() {
            cancelLoad();
        }

        @Override
        protected void onReset() {
            super.onReset();
            cancelLoad();
            result = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateRunButton() {
        btnRun.setEnabled(!mRunning && mValidSuManagerPos >= 1 && !new File("/dev/df").exists());
    }

    private void runExploit() {
        try {
            int rc = ExploitRunner.run(mDeCtx, this);
            String msg = rc == 0 ? "DFRoot: SUCCESS"
                       : rc == 1 ? "DFRoot: Error - ksud nonzero exit"
                       : rc == 2 ? "DFRoot: Error - check logcat & dmesg"
                       : "DFRoot: Error - failed to patch files";
            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            mMain.post(() -> {
                mRunning = false;
                updateRunButton();
            });
        }
    }

    private static class SuManagerEntry {
        final String packageName;
        final CharSequence label;
        final Drawable icon;

        SuManagerEntry(String pkg, CharSequence label, Drawable icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static class SuManagerAdapter extends ArrayAdapter<SuManagerEntry> {
        SuManagerAdapter(Context ctx, List<SuManagerEntry> items) {
            super(ctx, R.layout.item_su_manager, items);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.FALSE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager_closed, parent, false);
            v.setTag(Boolean.FALSE);
            return bindClosedView(pos, v);
        }

        @Override
        public View getDropDownView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.TRUE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager, parent, false);
            v.setTag(Boolean.TRUE);
            return bindView(pos, v);
        }

        @Override
        public boolean isEnabled(int pos) {
            return getItem(pos).packageName != null;
        }

        private View bindClosedView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ImageView icon = v.findViewById(R.id.iconApp);
            TextView label = v.findViewById(R.id.labelApp);
            if (e.packageName == null) {
                icon.setVisibility(View.GONE);
                label.setVisibility(View.VISIBLE);
                label.setText(e.label);
            } else {
                icon.setVisibility(View.VISIBLE);
                label.setVisibility(View.GONE);
                icon.setImageDrawable(e.icon);
            }
            return v;
        }

        private View bindView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ((ImageView) v.findViewById(R.id.iconApp)).setImageDrawable(e.icon);
            ((TextView)  v.findViewById(R.id.labelApp)).setText(e.label);
            return v;
        }
    }
}
